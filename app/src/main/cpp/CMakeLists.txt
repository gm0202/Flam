cmake_minimum_required(VERSION 3.22.1)

# Set the name of the library
project(edgeviewer)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Define the library
add_library(
        native-lib
        SHARED
        native-lib.cpp
)

# Include directories
include_directories(${CMAKE_SOURCE_DIR}/include)

# Link libraries
target_link_libraries(
        native-lib
        # Add other libraries to link against here
        android
        log
        # Add OpenCV or other libraries here if needed
)
