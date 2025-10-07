cmake_minimum_required(VERSION 3.18.1)

# Set project name
project(edgeviewer CXX)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Set OpenCV paths
set(OPENCV_ANDROID_SDK "C:/Users/91852/Downloads/opencv-4.12.0-android-sdk/OpenCV-android-sdk/sdk/native")
set(OPENCV_INCLUDE_DIR "${OPENCV_ANDROID_SDK}/jni/include")
set(OPENCV_LIB_DIR "${OPENCV_ANDROID_SDK}/libs/${ANDROID_ABI}")

# Add OpenCV include directories
include_directories(${OPENCV_INCLUDE_DIR})

# Create native library
add_library(
    native-lib
    SHARED
    native-lib.cpp
)

# Import OpenCV shared library
add_library(opencv_java4 SHARED IMPORTED)
set_target_properties(opencv_java4 PROPERTIES
    IMPORTED_LOCATION "${OPENCV_LIB_DIR}/libopencv_java4.so"
)

# Find log library
find_library(log-lib log)

# Link libraries
target_link_libraries(
    native-lib
    opencv_java4
    ${log-lib}
    android
    c++_shared
)

# Set the output directory for the shared library
set_target_properties(native-lib PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/${ANDROID_ABI}
)
