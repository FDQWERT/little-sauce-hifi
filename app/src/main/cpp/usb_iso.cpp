// LittleSauce HiFi —— usbfs isochronous 独占输出
//
// 直接对 /dev/bus/usb 走 USBDEVFS_SUBMITURB / REAPURB 的 ISO URB 队列，
// 自己按 microframe 精确分配每个包的长度（用整数累加器消化小数帧），
// 数据不进 AudioFlinger、不经过系统音量、不重采样。
//
// 依赖：内核 usbfs + app 对设备节点的 USB 权限。若接口被 snd-usb-audio
// 占用且无权限断开，会返回明确错误，由上层回落到系统通路。

#include <jni.h>
#include <android/log.h>

#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>

#include <sys/ioctl.h>
#include <sys/types.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "LcsUsbIso"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef USBDEVFS_GET_SPEED
#define USBDEVFS_GET_SPEED _IO('U', 31)
#endif
#ifndef USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER
#define USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER 1
#endif
#ifndef USBDEVFS_DISCONNECT_CLAIM_EXCEPT_DRIVER
#define USBDEVFS_DISCONNECT_CLAIM_EXCEPT_DRIVER 2
#endif
#ifndef USBDEVFS_MAXDRIVERNAME
#define USBDEVFS_MAXDRIVERNAME 255
#endif

namespace {

constexpr int kMaxPacketsPerUrb = 8;

std::mutex g_errMu;
std::string g_lastError;

void setError(const char *what, int err) {
    std::lock_guard<std::mutex> lk(g_errMu);
    char b[256];
    snprintf(b, sizeof(b), "%s: %s (%d)", what, err ? strerror(err) : "unknown", err);
    g_lastError = b;
    LOGE("%s", b);
}

struct Urb {
    struct usbdevfs_urb *u = nullptr;
    unsigned char *buf = nullptr;
    int bufCap = 0;
    int packets = 0;
    int pktLen[kMaxPacketsPerUrb] = {0};
    int used = 0;
    bool inFlight = false;
};

struct Ctx {
    int fd = -1;
    int ifIndex = 0;
    int alt = 0;
    int ep = 0;
    int maxPacket = 0;
    int interval = 0;
    int bps = 3;
    int channels = 2;
    int rate = 48000;

    int speed = 3;              // 2=full 3=high
    int pps = 8000;             // packets(=microframes) per second
    int frameBytes = 6;         // channels * bps
    int64_t carry = 0;          // 亚毫秒级帧数余数，单位 = 帧 * pps

    int urbCount = 0;
    std::vector<Urb *> urbs;
    std::deque<Urb *> freeList;

    std::mutex mu;
    std::condition_variable cv;
    std::atomic<bool> running{false};

    std::atomic<int64_t> framesWritten{0};
    std::atomic<int64_t> dropped{0};
    std::atomic<int64_t> urbErrors{0};

    int lastErr = 0;
};

Ctx *asCtx(jlong h) { return reinterpret_cast<Ctx *>(static_cast<intptr_t>(h)); }
jlong toHandle(Ctx *c) { return static_cast<jlong>(reinterpret_cast<intptr_t>(c)); }

bool submitUrb(Ctx *c, Urb *b) {
    struct usbdevfs_urb *u = b->u;
    memset(u, 0, sizeof(struct usbdevfs_urb) +
                     sizeof(struct usbdevfs_iso_packet_desc) * b->packets);
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = static_cast<unsigned char>(c->ep);
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer = b->buf;
    u->buffer_length = b->used;
    u->number_of_packets = b->packets;
    u->usercontext = b;
    int total = 0;
    for (int i = 0; i < b->packets; ++i) {
        u->iso_frame_desc[i].length = b->pktLen[i];
        total += b->pktLen[i];
    }
    b->used = total;

    int r = ioctl(c->fd, USBDEVFS_SUBMITURB, u);
    if (r < 0) {
        c->lastErr = errno;
        b->used = 0;
        return false;
    }
    b->inFlight = true;
    return true;
}

// 回收一个已完成的 URB；成功返回 true 并把它放回 freeList
bool reapOne(Ctx *c) {
    struct usbdevfs_urb *done = nullptr;
    int r = ioctl(c->fd, USBDEVFS_REAPURBNDELAY, &done);
    if (r < 0 || done == nullptr) return false;

    auto *b = reinterpret_cast<Urb *>(done->usercontext);
    if (b == nullptr) return false;

    if (done->status < 0) {
        c->urbErrors++;
        if (done->status != -EXDEV && done->status != -EOVERFLOW && done->status != -ENOENT) {
            c->lastErr = -done->status;
        }
    }
    int bad = 0;
    for (int i = 0; i < done->number_of_packets; ++i) {
        const int st = done->iso_frame_desc[i].status;
        if (st < 0) bad++;
    }
    if (bad > 0) {
        c->dropped += bad;
        if (bad < done->number_of_packets) c->urbErrors++;
    }

    b->inFlight = false;
    b->used = 0;
    {
        std::lock_guard<std::mutex> lk(c->mu);
        c->freeList.push_back(b);
    }
    c->cv.notify_one();
    return true;
}

void discardAndReap(Ctx *c) {
    for (auto *b : c->urbs) {
        if (b->inFlight) {
            ioctl(c->fd, USBDEVFS_DISCARDURB, b->u);
        }
    }
    // 收干净
    for (int i = 0; i < 200; ++i) {
        bool any = false;
        for (auto *b : c->urbs) {
            if (b->inFlight) { any = true; break; }
        }
        if (!any) break;
        if (!reapOne(c)) usleep(500);
    }
    std::lock_guard<std::mutex> lk(c->mu);
    c->freeList.clear();
    for (auto *b : c->urbs) {
        b->inFlight = false;
        b->used = 0;
        c->freeList.push_back(b);
    }
}

int claimInterface(int fd, int ifNum, std::string &err) {
    int n = ifNum;
    if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &n) == 0) return 0;
    const int first = errno;

    // 接口被内核音频驱动占着：尝试断开后接管
    struct usbdevfs_disconnect_claim dc;
    memset(&dc, 0, sizeof(dc));
    dc.interface = static_cast<unsigned int>(ifNum);
    dc.flags = USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER;
    strncpy(dc.driver, "snd-usb-audio", sizeof(dc.driver) - 1);
    if (ioctl(fd, USBDEVFS_DISCONNECT_CLAIM, &dc) == 0) return 0;
    const int second = errno;

    // 老接口：只断驱动，再自己 claim
    if (ioctl(fd, USBDEVFS_DISCONNECT, &n) == 0) {
        if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &n) == 0) return 0;
    }

    char b[192];
    snprintf(b, sizeof(b),
             "接口被占用且无法接管（claim=%s, disconnect_claim=%s）",
             strerror(first), strerror(second));
    err = b;
    return -1;
}

int detectSpeed(Ctx *c) {
    int s = ioctl(c->fd, USBDEVFS_GET_SPEED, 0);
    if (s > 0 && s <= 6) return s;
    // 启发式：全速 isoc 单包上限 1023 字节
    return c->maxPacket >= 1024 ? 3 : 2;
}

void releaseCtx(Ctx *c) {
    if (!c) return;
    c->running = false;
    c->cv.notify_all();
    if (c->fd >= 0) {
        discardAndReap(c);
        int n = c->ifIndex;
        ioctl(c->fd, USBDEVFS_RELEASEINTERFACE, &n);
    }
    for (auto *b : c->urbs) {
        if (b->u) free(b->u);
        if (b->buf) free(b->buf);
        delete b;
    }
    c->urbs.clear();
    if (c->fd >= 0) close(c->fd);
    c->fd = -1;
    delete c;
}

}  // namespace

// ---------------------------------------------------------------- JNI

extern "C" JNIEXPORT jlong JNICALL
Java_com_lcs_hifi_native_UsbIso_nativeOpen(JNIEnv *env, jobject,
                                           jint fd, jint ifIndex, jint alt, jint epAddr,
                                           jint maxPacket, jint interval,
                                           jint bytesPerSample, jint channels, jint rate) {
    if (fd < 0) { setError("bad fd", 0); return 0; }
    if (maxPacket <= 0 || channels <= 0 || bytesPerSample <= 0 || rate <= 0) {
        setError("bad params", 0);
        return 0;
    }

    int dupFd = dup(fd);
    if (dupFd < 0) { setError("dup", errno); return 0; }

    auto *c = new Ctx();
    c->fd = dupFd;
    c->ifIndex = ifIndex;
    c->alt = alt;
    c->ep = epAddr;
    c->maxPacket = maxPacket;
    c->interval = interval;
    c->bps = bytesPerSample;
    c->channels = channels;
    c->rate = rate;
    c->frameBytes = channels * bytesPerSample;

    std::string cerr;
    if (claimInterface(dupFd, ifIndex, cerr) != 0) {
        std::lock_guard<std::mutex> lk(g_errMu);
        g_lastError = cerr;
        close(dupFd);
        delete c;
        return 0;
    }

    // alt setting
    struct usbdevfs_setinterface si;
    memset(&si, 0, sizeof(si));
    si.interface = static_cast<unsigned int>(ifIndex);
    si.altsetting = static_cast<unsigned int>(alt);
    if (ioctl(dupFd, USBDEVFS_SETINTERFACE, &si) != 0) {
        const int e = errno;
        releaseCtx(c);
        setError("SETINTERFACE", e);
        return 0;
    }

    c->speed = detectSpeed(c);
    c->pps = (c->speed >= 3) ? 8000 : 1000;

    // 每 microframe 需要多少帧：rate / pps，可能不是整数
    const int maxFramesPerPacket = maxPacket / c->frameBytes;
    if (maxFramesPerPacket <= 0) {
        releaseCtx(c);
        setError("端点包长小于一帧", 0);
        return 0;
    }
    const int ceilFrames = (rate + c->pps - 1) / c->pps;
    if (ceilFrames > maxFramesPerPacket) {
        releaseCtx(c);
        setError("端点包长不足以承载该采样率/位宽", EINVAL);
        return 0;
    }

    // 队列深度：HS 16x8 ≈ 16ms，FS 8x4 ≈ 32ms（太小容易 underrun）
    const int packetsPerUrb = (c->speed >= 3) ? kMaxPacketsPerUrb : 4;
    const int urbCount = (c->speed >= 3) ? 16 : 8;
    c->urbCount = urbCount;

    for (int i = 0; i < urbCount; ++i) {
        auto *b = new Urb();
        const size_t usz = sizeof(struct usbdevfs_urb) +
                           sizeof(struct usbdevfs_iso_packet_desc) * packetsPerUrb;
        b->u = static_cast<struct usbdevfs_urb *>(calloc(1, usz));
        b->bufCap = packetsPerUrb * maxPacket;
        if (posix_memalign(reinterpret_cast<void **>(&b->buf), 4096, b->bufCap) != 0) {
            b->buf = static_cast<unsigned char *>(malloc(b->bufCap));
        }
        if (b->buf) memset(b->buf, 0, b->bufCap);
        b->packets = packetsPerUrb;
        c->urbs.push_back(b);
        c->freeList.push_back(b);
    }

    c->running = true;
    c->lastErr = 0;
    LOGI("open ok: if=%d alt=%d ep=0x%02x pkt=%d speed=%d pps=%d rate=%d ch=%d bps=%d urbs=%dx%d",
         ifIndex, alt, epAddr, maxPacket, c->speed, c->pps, rate, channels, bytesPerSample,
         urbCount, packetsPerUrb);
    return toHandle(c);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lcs_hifi_native_UsbIso_nativeStart(JNIEnv *, jobject, jlong h) {
    Ctx *c = asCtx(h);
    if (!c) return -1;
    c->running = true;
    c->framesWritten = 0;
    c->dropped = 0;
    c->urbErrors = 0;
    c->carry = 0;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lcs_hifi_native_UsbIso_nativeWrite(JNIEnv *env, jobject, jlong h,
                                            jbyteArray data, jint off, jint len) {
    Ctx *c = asCtx(h);
    if (!c || !c->running) return -1;
    if (len <= 0) return 0;
    if (off < 0) return -2;

    jbyte *src = env->GetByteArrayElements(data, nullptr);
    if (!src) return -3;

    int consumed = 0;
    int64_t waitedUs = 0;

    while (consumed < len && c->running) {
        Urb *b = nullptr;
        {
            std::lock_guard<std::mutex> lk(c->mu);
            if (!c->freeList.empty()) {
                b = c->freeList.front();
                c->freeList.pop_front();
            }
        }

        if (!b) {
            bool got = false;
            for (int i = 0; i < 4 && !got; ++i) got = reapOne(c);
            if (!got) {
                if (waitedUs > 400000) {  // 400ms 供不上流
                    break;
                }
                usleep(250);
                waitedUs += 250;
                continue;
            }
            continue;
        }

        waitedUs = 0;

        // 按 microframe 精确分配本次 URB 内每个包的字节数
        int total = 0;
        for (int p = 0; p < b->packets; ++p) {
            c->carry += c->rate;
            int frames = static_cast<int>(c->carry / c->pps);
            c->carry -= static_cast<int64_t>(frames) * c->pps;
            int bytes = frames * c->frameBytes;
            if (bytes > c->maxPacket) {
                c->dropped++;
                bytes = c->maxPacket;
            }
            b->pktLen[p] = bytes;
            total += bytes;
        }

        if (total > b->bufCap) total = b->bufCap;
        int take = len - consumed;
        if (take > total) take = total;
        if (take > 0) {
            memcpy(b->buf, src + off + consumed, static_cast<size_t>(take));
        }
        if (take < total) {
            // 欠载：剩余包补零静音，保证 URB 完整提交
            memset(b->buf + take, 0, static_cast<size_t>(total - take));
            c->dropped++;
        }
        b->used = total;
        consumed += take;
        c->framesWritten += take / (c->frameBytes > 0 ? c->frameBytes : 1);

        if (!submitUrb(c, b)) {
            const int e = c->lastErr;
            {
                std::lock_guard<std::mutex> lk(c->mu);
                b->inFlight = false;
                b->used = 0;
                c->freeList.push_back(b);
            }
            env->ReleaseByteArrayElements(data, src, JNI_ABORT);
            if (consumed > 0) return consumed;
            return e > 0 ? -e : -4;
        }
    }

    env->ReleaseByteArrayElements(data, src, JNI_ABORT);
    if (consumed == 0) {
        // 队列长期供不上（设备停流/被拔），交给上层判断
        c->lastErr = ETIMEDOUT;
        return -ETIMEDOUT;
    }
    return consumed;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lcs_hifi_native_UsbIso_nativeStop(JNIEnv *, jobject, jlong h) {
    Ctx *c = asCtx(h);
    if (!c) return;
    c->running = false;
    discardAndReap(c);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lcs_hifi_native_UsbIso_nativeClose(JNIEnv *, jobject, jlong h) {
    Ctx *c = asCtx(h);
    if (!c) return;
    releaseCtx(c);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lcs_hifi_native_UsbIso_nativeLastError(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lk(g_errMu);
    return env->NewStringUTF(g_lastError.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lcs_hifi_native_UsbIso_nativeStats(JNIEnv *env, jobject, jlong h) {
    Ctx *c = asCtx(h);
    if (!c) return env->NewStringUTF("no-context");
    char b[256];
    snprintf(b, sizeof(b), "%dx%d pkt=%d speed=%d rate=%d ch=%d bps=%d frames=%lld drop=%lld err=%lld",
             c->urbCount, c->urbs.empty() ? 0 : c->urbs[0]->packets,
             c->maxPacket, c->speed, c->rate, c->channels, c->bps,
             static_cast<long long>(c->framesWritten.load()),
             static_cast<long long>(c->dropped.load()),
             static_cast<long long>(c->urbErrors.load()));
    return env->NewStringUTF(b);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lcs_hifi_native_UsbIso_nativeSetAlt(JNIEnv *, jobject, jlong h, jint alt) {
    Ctx *c = asCtx(h);
    if (!c) return -1;
    c->running = false;
    discardAndReap(c);
    struct usbdevfs_setinterface si;
    memset(&si, 0, sizeof(si));
    si.interface = static_cast<unsigned int>(c->ifIndex);
    si.altsetting = static_cast<unsigned int>(alt);
    if (ioctl(c->fd, USBDEVFS_SETINTERFACE, &si) != 0) {
        c->lastErr = errno;
        setError("SETINTERFACE(alt)", errno);
        return -errno;
    }
    c->alt = alt;
    c->carry = 0;
    c->running = true;
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lcs_hifi_native_UsbIso_nativeFrames(JNIEnv *, jobject, jlong h) {
    Ctx *c = asCtx(h);
    return c ? static_cast<jlong>(c->framesWritten.load()) : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lcs_hifi_native_UsbIso_nativeDropped(JNIEnv *, jobject, jlong h) {
    Ctx *c = asCtx(h);
    return c ? static_cast<jlong>(c->dropped.load()) : 0;
}
