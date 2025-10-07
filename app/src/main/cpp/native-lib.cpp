// Include standard C headers FIRST (required for NDK)
#include <stdint.h>
#include <stddef.h>

// Include JNI and Android headers
#include <jni.h>
#include <android/log.h>

// Include OpenCV headers
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

// Android logging macros
#define LOG_TAG "EdgeViewerNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace cv;

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_edgeviewer_MainActivity_processImage(
    JNIEnv* env,
    jobject /* this */,
    jlong matAddrInput,
    jlong matAddrResult) {
    
    try {
        // Convert input to OpenCV Mat
        Mat& input = *(Mat*)matAddrInput;
        Mat& result = *(Mat*)matAddrResult;
        
        // Process the image (edge detection example)
        cvtColor(input, result, COLOR_RGBA2GRAY);
        Canny(result, result, 50, 150);
        
        LOGI("Image processed successfully");
    } 
    catch (const cv::Exception& e) {
        LOGE("OpenCV error: %s", e.what());
    } 
    catch (...) {
        LOGE("Unknown error in native code");
    }
}

} // extern "C"