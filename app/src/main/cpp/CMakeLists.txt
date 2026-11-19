cmake_minimum_required(VERSION 3.22.1)
project(hifiusb CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(hifiusb SHARED
        usb_iso.cpp)

target_compile_options(hifiusb PRIVATE
        -O2
        -fvisibility=hidden
        -Wall
        -Wextra
        -Wno-unused-parameter)

find_library(log-lib log)
target_link_libraries(hifiusb ${log-lib})
