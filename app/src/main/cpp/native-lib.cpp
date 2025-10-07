// Include standard C headers FIRST (required for NDK)
#include <stdint.h>
#include <stddef.h>

// Include JNI and Android headers
#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstring>

// Include OpenCV headers
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#define LOG_TAG "EdgeViewerNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace cv;

extern "C" {

/**
 * Process NV21 image data
 * @param data NV21 byte array
 * @param width Image width
 * @param height Image height
 * @param mode Processing mode (0 = raw, 1 = edge detection)
 * @return Processed NV21 byte array
 */
JNIEXPORT jbyteArray JNICALL
Java_com_example_edgeviewer_NativeLib_processNV21(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray data,
        jint width,
        jint height,
        jint mode) {
    
    try {
        // Get input data
        jbyte* nv21Data = env->GetByteArrayElements(data, nullptr);
        if (nv21Data == nullptr) {
            LOGE("Failed to get byte array elements");
            return data;
        }
        
        // Convert NV21 to OpenCV Mat (grayscale)
        Mat yuvMat(height + height / 2, width, CV_8UC1, (unsigned char*)nv21Data);
        Mat grayMat(height, width, CV_8UC1);
        Mat resultMat;
        
        // Extract Y channel (grayscale)
        yuvMat(Rect(0, 0, width, height)).copyTo(grayMat);
        
        // Process based on mode
        switch (mode) {
            case 0: // Raw - no processing
                resultMat = grayMat;
                break;
                
            case 1: // Edge detection
                Canny(grayMat, resultMat, 50, 150);
                break;
                
            default:
                resultMat = grayMat;
                break;
        }
        
        // Create output byte array
        int outputSize = width * height + width * height / 2; // NV21 size
        jbyteArray outputArray = env->NewByteArray(outputSize);
        
        if (outputArray == nullptr) {
            LOGE("Failed to create output array");
            env->ReleaseByteArrayElements(data, nv21Data, JNI_ABORT);
            return data;
        }
        
        // Copy processed Y channel
        jbyte* outputData = env->GetByteArrayElements(outputArray, nullptr);
        memcpy(outputData, resultMat.data, width * height);
        
        // Copy UV channels (unchanged for grayscale)
        memcpy(outputData + width * height, nv21Data + width * height, width * height / 2);
        
        // Release arrays
        env->ReleaseByteArrayElements(data, nv21Data, JNI_ABORT);
        env->ReleaseByteArrayElements(outputArray, outputData, 0);
        
        return outputArray;
        
    } catch (const cv::Exception& e) {
        LOGE("OpenCV error in processNV21: %s", e.what());
        return data;
    } catch (...) {
        LOGE("Unknown error in processNV21");
        return data;
    }
}

// This function is called when the library is loaded
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    
    LOGI("EdgeViewer native library loaded successfully");
    return JNI_VERSION_1_6;
}

} // extern "C"
