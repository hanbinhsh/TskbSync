cmake_minimum_required(VERSION 3.20)
project(tskbsync_native_streamer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(tskbsync_native_streamer
    src/main.cpp
)

target_compile_definitions(tskbsync_native_streamer PRIVATE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
)

target_link_libraries(tskbsync_native_streamer PRIVATE
    mf
    mfplat
    mfuuid
    wmcodecdspuuid
    ole32
    gdi32
    user32
    d3d11
    dxgi
    windowsapp
    runtimeobject
)
