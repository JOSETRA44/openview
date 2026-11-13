// Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
// OpenView's own OpenXR client. No runtime or tracking implementation is linked here.
#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#define INFO(...) __android_log_print(ANDROID_LOG_INFO, "OpenViewXR", __VA_ARGS__)
#define ERROR(...) __android_log_print(ANDROID_LOG_ERROR, "OpenViewXR", __VA_ARGS__)

namespace {
constexpr float PI = 3.14159265358979323846f;
using Mat = std::array<float, 16>;
Mat identity() { return {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}; }
Mat multiply(const Mat &a, const Mat &b) {
    Mat c{};
    for (int col=0; col<4; ++col) for (int row=0; row<4; ++row)
        for (int k=0; k<4; ++k) c[col*4+row] += a[k*4+row]*b[col*4+k];
    return c;
}
Mat poseMatrix(const XrPosef &p) {
    auto q=p.orientation;
    Mat m = identity();
    m[0]=1-2*(q.y*q.y+q.z*q.z); m[1]=2*(q.x*q.y+q.z*q.w); m[2]=2*(q.x*q.z-q.y*q.w);
    m[4]=2*(q.x*q.y-q.z*q.w); m[5]=1-2*(q.x*q.x+q.z*q.z); m[6]=2*(q.y*q.z+q.x*q.w);
    m[8]=2*(q.x*q.z+q.y*q.w); m[9]=2*(q.y*q.z-q.x*q.w); m[10]=1-2*(q.x*q.x+q.y*q.y);
    m[12]=p.position.x; m[13]=p.position.y; m[14]=p.position.z;
    return m;
}
Mat viewMatrix(const XrPosef &p) {
    const auto m=poseMatrix(p);
    Mat r=identity();
    for(int i=0;i<3;++i) for(int j=0;j<3;++j) r[i*4+j]=m[j*4+i];
    for(int i=0;i<3;++i) r[12+i]=-(r[i]*p.position.x+r[4+i]*p.position.y+r[8+i]*p.position.z);
    return r;
}
Mat projection(const XrFovf &f) {
    const float l=std::tan(f.angleLeft), r=std::tan(f.angleRight);
    const float b=std::tan(f.angleDown), t=std::tan(f.angleUp), n=.05f, far=60.f;
    Mat m{};
    m[0]=2/(r-l); m[5]=2/(t-b); m[8]=(r+l)/(r-l); m[9]=(t+b)/(t-b);
    m[10]=-(far+n)/(far-n); m[11]=-1; m[14]=-2*far*n/(far-n);
    return m;
}
void xrCheck(XrResult result, const char *operation) {
    if(XR_FAILED(result)) throw std::runtime_error(std::string(operation)+" (OpenXR "+std::to_string(result)+")");
}
#define XR(call) xrCheck((call), #call)
GLuint shader(GLenum type, const char *src) {
    GLuint sh=glCreateShader(type); glShaderSource(sh,1,&src,nullptr); glCompileShader(sh);
    GLint ok=0; glGetShaderiv(sh,GL_COMPILE_STATUS,&ok);
    if(!ok) { char text[2048]{}; glGetShaderInfoLog(sh,sizeof(text),nullptr,text); glDeleteShader(sh); throw std::runtime_error(text); }
    return sh;
}
GLuint program(const char *vs, const char *fs) {
    GLuint v=shader(GL_VERTEX_SHADER,vs), f=shader(GL_FRAGMENT_SHADER,fs), p=glCreateProgram();
    glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p); glDeleteShader(v); glDeleteShader(f);
    GLint ok=0; glGetProgramiv(p,GL_LINK_STATUS,&ok);
    if(!ok) { char text[2048]{}; glGetProgramInfoLog(p,sizeof(text),nullptr,text); glDeleteProgram(p); throw std::runtime_error(text); }
    return p;
}
struct Vertex { float x,y,z,r,g,b; };
struct Eye {
    XrSwapchain swapchain=XR_NULL_HANDLE;
    uint32_t width=0,height=0;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
    GLuint fbo=0,depth=0;
};

struct Experience {
    std::atomic<bool> stop{false}, cameraWanted{false}, recenter{false};
    jobject activity=nullptr;
    JavaVM *vm=nullptr;
    JNIEnv *env=nullptr;
    jmethodID cameraCreate=nullptr,cameraUpdate=nullptr,cameraRelease=nullptr;
    jfloatArray cameraTransformArray=nullptr;
    XrInstance instance=XR_NULL_HANDLE;
    XrSession session=XR_NULL_HANDLE;
    XrSpace local=XR_NULL_HANDLE,base=XR_NULL_HANDLE,head=XR_NULL_HANDLE;
    XrSystemId system=XR_NULL_SYSTEM_ID;
    bool running=false, cameraActive=false;
    EGLDisplay display=EGL_NO_DISPLAY;
    EGLContext context=EGL_NO_CONTEXT;
    EGLSurface surface=EGL_NO_SURFACE;
    EGLConfig config=nullptr;
    std::vector<Eye> eyes;
    GLuint sceneProgram=0,cameraProgram=0,vao=0,vbo=0,cameraTexture=0;
    Mat cameraTransform=identity();
    std::vector<Vertex> grid,objects;
    uint64_t frames=0,cameraFrames=0;

    void javaCheck() {
        if(env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); throw std::runtime_error("Error de cámara Android"); }
    }
    void create() {
        jclass cls=env->GetObjectClass(activity);
        cameraCreate=env->GetMethodID(cls,"createCameraTexture","(I)Z");
        cameraUpdate=env->GetMethodID(cls,"updateCameraTexture","([F)J");
        cameraRelease=env->GetMethodID(cls,"releaseCameraTexture","()V");
        env->DeleteLocalRef(cls); javaCheck();
        if(!cameraCreate||!cameraUpdate||!cameraRelease) throw std::runtime_error("Camera JNI contract missing");
        cameraTransformArray=env->NewFloatArray(16);

        PFN_xrInitializeLoaderKHR initialize=nullptr;
        XR(xrGetInstanceProcAddr(XR_NULL_HANDLE,"xrInitializeLoaderKHR",reinterpret_cast<PFN_xrVoidFunction*>(&initialize)));
        XrLoaderInitInfoAndroidKHR loader{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        loader.applicationVM=vm; loader.applicationContext=activity;
        XR(initialize(reinterpret_cast<XrLoaderInitInfoBaseHeaderKHR*>(&loader)));
        const char *extensions[]={XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
        XrInstanceCreateInfoAndroidKHR android{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
        android.applicationVM=vm; android.applicationActivity=activity;
        XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO}; ci.next=&android;
        std::strcpy(ci.applicationInfo.applicationName,"OpenView Space");
        std::strcpy(ci.applicationInfo.engineName,"OpenView");
        ci.applicationInfo.apiVersion=XR_MAKE_VERSION(1,0,0);
        ci.enabledExtensionCount=2; ci.enabledExtensionNames=extensions;
        XR(xrCreateInstance(&ci,&instance));
        XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO}; systemInfo.formFactor=XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
        XR(xrGetSystem(instance,&systemInfo,&system));
        PFN_xrGetOpenGLESGraphicsRequirementsKHR requirementsFn=nullptr;
        XR(xrGetInstanceProcAddr(instance,"xrGetOpenGLESGraphicsRequirementsKHR",reinterpret_cast<PFN_xrVoidFunction*>(&requirementsFn)));
        XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
        XR(requirementsFn(instance,system,&requirements));

        display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if(display==EGL_NO_DISPLAY || !eglInitialize(display,nullptr,nullptr)) throw std::runtime_error("eglInitialize failed");
        const EGLint attributes[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
        EGLint count=0;
        if(!eglChooseConfig(display,attributes,&config,1,&count)||count==0) throw std::runtime_error("No GLES3 EGL config");
        const EGLint contextAttributes[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
        const EGLint surfaceAttributes[]={EGL_WIDTH,16,EGL_HEIGHT,16,EGL_NONE};
        context=eglCreateContext(display,config,EGL_NO_CONTEXT,contextAttributes);
        surface=eglCreatePbufferSurface(display,config,surfaceAttributes);
        if(context==EGL_NO_CONTEXT||surface==EGL_NO_SURFACE||!eglMakeCurrent(display,surface,surface,context))
            throw std::runtime_error("No GLES3 context");
        GLint major=0,minor=0; glGetIntegerv(GL_MAJOR_VERSION,&major); glGetIntegerv(GL_MINOR_VERSION,&minor);
        auto version=XR_MAKE_VERSION(major,minor,0);
        if(version<requirements.minApiVersionSupported||version>requirements.maxApiVersionSupported)
            throw std::runtime_error("GLES version outside runtime requirements");
        XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
        binding.display=display; binding.config=config; binding.context=context;
        XrSessionCreateInfo si{XR_TYPE_SESSION_CREATE_INFO}; si.next=&binding; si.systemId=system;
        XR(xrCreateSession(instance,&si,&session));
        XrReferenceSpaceCreateInfo spaceInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
        spaceInfo.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_LOCAL; spaceInfo.poseInReferenceSpace.orientation.w=1;
        XR(xrCreateReferenceSpace(session,&spaceInfo,&base));
        XR(xrCreateReferenceSpace(session,&spaceInfo,&local));
        spaceInfo.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_VIEW;
        XR(xrCreateReferenceSpace(session,&spaceInfo,&head));

        uint32_t viewCount=0;
        XR(xrEnumerateViewConfigurationViews(instance,system,XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,0,&viewCount,nullptr));
        if(viewCount!=2) throw std::runtime_error("This experience requires stereo views");
        std::vector<XrViewConfigurationView> configs(viewCount,{XR_TYPE_VIEW_CONFIGURATION_VIEW});
        XR(xrEnumerateViewConfigurationViews(instance,system,XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,viewCount,&viewCount,configs.data()));
        uint32_t formatCount=0; XR(xrEnumerateSwapchainFormats(session,0,&formatCount,nullptr));
        std::vector<int64_t> formats(formatCount); XR(xrEnumerateSwapchainFormats(session,formatCount,&formatCount,formats.data()));
        int64_t format=0;
        for(auto candidate:{GL_SRGB8_ALPHA8,GL_RGBA8})
            if(std::find(formats.begin(),formats.end(),candidate)!=formats.end()) { format=candidate; break; }
        if(!format) throw std::runtime_error("No RGBA swapchain format");
        eyes.resize(viewCount);
        for(uint32_t i=0;i<viewCount;++i) {
            auto &eye=eyes[i]; eye.width=configs[i].recommendedImageRectWidth; eye.height=configs[i].recommendedImageRectHeight;
            XrSwapchainCreateInfo sc{XR_TYPE_SWAPCHAIN_CREATE_INFO};
            sc.usageFlags=XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT|XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
            sc.format=format; sc.sampleCount=1; sc.width=eye.width; sc.height=eye.height;
            sc.faceCount=1; sc.arraySize=1; sc.mipCount=1;
            XR(xrCreateSwapchain(session,&sc,&eye.swapchain));
            uint32_t imageCount=0; XR(xrEnumerateSwapchainImages(eye.swapchain,0,&imageCount,nullptr));
            eye.images.resize(imageCount,{XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
            XR(xrEnumerateSwapchainImages(eye.swapchain,imageCount,&imageCount,reinterpret_cast<XrSwapchainImageBaseHeader*>(eye.images.data())));
            glGenFramebuffers(1,&eye.fbo); glGenRenderbuffers(1,&eye.depth);
            glBindRenderbuffer(GL_RENDERBUFFER,eye.depth); glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT24,eye.width,eye.height);
        }
        sceneProgram=program(
            "#version 300 es\nlayout(location=0) in vec3 pos;layout(location=1) in vec3 color;uniform mat4 mvp;out vec3 c;void main(){c=color;gl_Position=mvp*vec4(pos,1.0);}",
            "#version 300 es\nprecision mediump float;in vec3 c;out vec4 outColor;void main(){outColor=vec4(c,1.0);}");
        cameraProgram=program(
            "#version 300 es\nuniform mat4 transform;out vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=(transform*vec4(p,0,1)).xy;gl_Position=vec4(p*2.0-1.0,0,1);}",
            "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision mediump float;uniform samplerExternalOES image;in vec2 uv;out vec4 outColor;void main(){outColor=vec4(texture(image,uv).rgb,1.0);}");
        glGenVertexArrays(1,&vao); glGenBuffers(1,&vbo);
        glGenTextures(1,&cameraTexture); glBindTexture(GL_TEXTURE_EXTERNAL_OES,cameraTexture);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        for(int i=-12;i<=12;++i) {
            const float v=float(i), shade=(i%4==0)?.19f:.085f;
            grid.push_back({v,-1.5f,-12,.08f,shade,shade+.025f}); grid.push_back({v,-1.5f,12,.08f,shade,shade+.025f});
            grid.push_back({-12,-1.5f,v,.08f,shade,shade+.025f}); grid.push_back({12,-1.5f,v,.08f,shade,shade+.025f});
        }
        INFO("OpenXR initialized; stereo=%u GLES=%d.%d eye=%ux%u",viewCount,major,minor,eyes[0].width,eyes[0].height);
    }

    void updateCamera() {
        if(cameraWanted.load() && !cameraActive) {
            cameraActive=env->CallBooleanMethod(activity,cameraCreate,static_cast<jint>(cameraTexture)); javaCheck();
            if(!cameraActive) {
                env->CallVoidMethod(activity,cameraRelease); javaCheck();
                cameraWanted=false; INFO("Camera unavailable; falling back to space");
            }
        } else if(!cameraWanted.load() && cameraActive) {
            env->CallVoidMethod(activity,cameraRelease); javaCheck(); cameraActive=false; cameraFrames=0;
        }
        if(cameraActive) {
            cameraFrames=env->CallLongMethod(activity,cameraUpdate,cameraTransformArray); javaCheck();
            if(cameraFrames) env->GetFloatArrayRegion(cameraTransformArray,0,16,cameraTransform.data());
        }
    }
    void events() {
        XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
        XrResult result;
        while((result=xrPollEvent(instance,&event))==XR_SUCCESS) {
            if(event.type==XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                auto state=reinterpret_cast<XrEventDataSessionStateChanged*>(&event)->state;
                INFO("session state=%d",state);
                if(state==XR_SESSION_STATE_READY && !running && !stop) {
                    XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO}; begin.primaryViewConfigurationType=XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                    XR(xrBeginSession(session,&begin)); running=true; INFO("session READY");
                } else if(state==XR_SESSION_STATE_STOPPING) {
                    INFO("session STOPPING"); if(running) XR(xrEndSession(session)); running=false;
                } else if(state==XR_SESSION_STATE_EXITING || state==XR_SESSION_STATE_LOSS_PENDING) stop=true;
            } else if(event.type==XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) stop=true;
            event={XR_TYPE_EVENT_DATA_BUFFER};
        }
        if(result!=XR_EVENT_UNAVAILABLE) xrCheck(result,"xrPollEvent");
    }
    void center(XrTime time) {
        XrSpaceLocation location{XR_TYPE_SPACE_LOCATION}; XR(xrLocateSpace(head,base,time,&location));
        if(!(location.locationFlags&XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) return;
        // Heading-only recenter retains a level floor. Never manufacture positional tracking.
        auto q=location.pose.orientation;
        const float yaw=std::atan2(2*(q.w*q.y+q.x*q.z),1-2*(q.y*q.y+q.x*q.x));
        XrReferenceSpaceCreateInfo ci{XR_TYPE_REFERENCE_SPACE_CREATE_INFO}; ci.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_LOCAL;
        ci.poseInReferenceSpace.orientation={0,std::sin(yaw/2),0,std::cos(yaw/2)};
        if(location.locationFlags&XR_SPACE_LOCATION_POSITION_VALID_BIT) ci.poseInReferenceSpace.position=location.pose.position;
        XrSpace next=XR_NULL_HANDLE; XR(xrCreateReferenceSpace(session,&ci,&next));
        xrDestroySpace(local); local=next; INFO("view recentered");
    }
    void drawVertices(const std::vector<Vertex> &vertices, GLenum primitive, const Mat &mvp) {
        glUseProgram(sceneProgram); glUniformMatrix4fv(glGetUniformLocation(sceneProgram,"mvp"),1,GL_FALSE,mvp.data());
        glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER,vbo);
        glBufferData(GL_ARRAY_BUFFER,vertices.size()*sizeof(Vertex),vertices.data(),GL_STREAM_DRAW);
        glEnableVertexAttribArray(0); glEnableVertexAttribArray(1);
        glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,sizeof(Vertex),nullptr);
        glVertexAttribPointer(1,3,GL_FLOAT,GL_FALSE,sizeof(Vertex),reinterpret_cast<void*>(3*sizeof(float)));
        glDrawArrays(primitive,0,static_cast<GLsizei>(vertices.size()));
    }
    void makeObjects(double seconds) {
        objects.clear();
        // Three floating sculptures and a far ring form a recognizable OpenView space.
        for(int object=0;object<3;++object) {
            const float a=float(seconds)*.25f+object, c=std::cos(a),s=std::sin(a);
            const float cx=(object-1)*1.4f, cy=-.1f+.12f*std::sin(float(seconds)*.6f+object), cz=-3.0f;
            const float points[6][3]={{0,.55f,0},{.45f,0,0},{0,0,.45f},{-.45f,0,0},{0,0,-.45f},{0,-.55f,0}};
            const int faces[8][3]={{0,1,2},{0,2,3},{0,3,4},{0,4,1},{5,2,1},{5,3,2},{5,4,3},{5,1,4}};
            for(int f=0;f<8;++f) for(int k=0;k<3;++k) {
                const float *p=points[faces[f][k]]; const float shade=.55f+float(f%4)*.14f;
                objects.push_back({cx+c*p[0]+s*p[2],cy+p[1],cz-s*p[0]+c*p[2],
                    (object==0?.84f:.35f)*shade,(object==0?.64f:.9f)*shade,(object==0?.38f:.76f)*shade});
            }
        }
        for(int i=0;i<80;++i) {
            const float a=i*2*PI/80,b=(i+1)*2*PI/80;
            const float r=1.8f,rr=1.84f;
            for(auto p:std::array<std::array<float,2>,6>{{{r*std::cos(a),r*std::sin(a)},{rr*std::cos(a),rr*std::sin(a)},
                {rr*std::cos(b),rr*std::sin(b)},{r*std::cos(a),r*std::sin(a)},{rr*std::cos(b),rr*std::sin(b)},{r*std::cos(b),r*std::sin(b)}}})
                objects.push_back({p[0],p[1]+.2f,-6.f,.2f,.55f,.5f});
        }
    }
    void frame(double seconds) {
        XrFrameWaitInfo wait{XR_TYPE_FRAME_WAIT_INFO}; XrFrameState state{XR_TYPE_FRAME_STATE}; XR(xrWaitFrame(session,&wait,&state));
        XrFrameBeginInfo begin{XR_TYPE_FRAME_BEGIN_INFO}; XR(xrBeginFrame(session,&begin));
        std::array<XrCompositionLayerProjectionView,2> projections{{{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW},{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}}};
        XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
        layer.space=local; layer.viewCount=2; layer.views=projections.data();
        const XrCompositionLayerBaseHeader *layers[]={reinterpret_cast<const XrCompositionLayerBaseHeader*>(&layer)};
        uint32_t layerCount=0;
        if(state.shouldRender) {
            if(recenter.exchange(false)) { center(state.predictedDisplayTime); layer.space=local; }
            updateCamera(); makeObjects(seconds);
            std::array<XrView,2> views{{{XR_TYPE_VIEW},{XR_TYPE_VIEW}}};
            XrViewLocateInfo locate{XR_TYPE_VIEW_LOCATE_INFO}; locate.viewConfigurationType=XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
            locate.displayTime=state.predictedDisplayTime; locate.space=local;
            XrViewState viewState{XR_TYPE_VIEW_STATE}; uint32_t count=0;
            XR(xrLocateViews(session,&locate,&viewState,2,&count,views.data()));
            if(count==2 && (viewState.viewStateFlags&XR_VIEW_STATE_ORIENTATION_VALID_BIT)) {
                for(uint32_t i=0;i<2;++i) {
                    auto &eye=eyes[i]; uint32_t index=0;
                    XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO}; XR(xrAcquireSwapchainImage(eye.swapchain,&acquire,&index));
                    XrSwapchainImageWaitInfo imageWait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO}; imageWait.timeout=XR_INFINITE_DURATION;
                    XR(xrWaitSwapchainImage(eye.swapchain,&imageWait));
                    glBindFramebuffer(GL_FRAMEBUFFER,eye.fbo);
                    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,eye.images[index].image,0);
                    glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,eye.depth);
                    if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE) throw std::runtime_error("Incomplete eye framebuffer");
                    glViewport(0,0,eye.width,eye.height); glDisable(GL_SCISSOR_TEST); glDisable(GL_CULL_FACE); glDisable(GL_BLEND);
                    glClearColor(.018f,.035f,.045f,1); glDepthMask(GL_TRUE); glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                    if(cameraActive && cameraFrames) {
                        glDisable(GL_DEPTH_TEST); glUseProgram(cameraProgram); glBindVertexArray(vao);
                        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_EXTERNAL_OES,cameraTexture);
                        glUniform1i(glGetUniformLocation(cameraProgram,"image"),0);
                        glUniformMatrix4fv(glGetUniformLocation(cameraProgram,"transform"),1,GL_FALSE,cameraTransform.data());
                        glDrawArrays(GL_TRIANGLES,0,3);
                    }
                    glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LESS);
                    auto mvp=multiply(projection(views[i].fov),viewMatrix(views[i].pose));
                    if(!cameraActive) drawVertices(grid,GL_LINES,mvp);
                    drawVertices(objects,GL_TRIANGLES,mvp);
                    glBindFramebuffer(GL_FRAMEBUFFER,0); glFlush();
                    XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO}; XR(xrReleaseSwapchainImage(eye.swapchain,&release));
                    auto &p=projections[i]; p.pose=views[i].pose; p.fov=views[i].fov; p.subImage.swapchain=eye.swapchain;
                    p.subImage.imageRect.extent={static_cast<int32_t>(eye.width),static_cast<int32_t>(eye.height)};
                }
                layerCount=1; ++frames;
            }
        }
        XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO}; end.displayTime=state.predictedDisplayTime;
        end.environmentBlendMode=XR_ENVIRONMENT_BLEND_MODE_OPAQUE; end.layerCount=layerCount; end.layers=layers;
        XR(xrEndFrame(session,&end));
    }
    void run() {
        create();
        const auto started=std::chrono::steady_clock::now(); auto lastLog=started;
        while(!stop.load()) {
            events();
            if(!running) { std::this_thread::sleep_for(std::chrono::milliseconds(20)); continue; }
            auto now=std::chrono::steady_clock::now();
            frame(std::chrono::duration<double>(now-started).count());
            if(now-lastLog>=std::chrono::seconds(1)) {
                INFO("frames=%llu cameraFrames=%llu mode=%s",static_cast<unsigned long long>(frames),
                    static_cast<unsigned long long>(cameraFrames),cameraActive?"camera":"space"); lastLog=now;
            }
        }
        if(running) {
            auto result=xrRequestExitSession(session);
            if(XR_SUCCEEDED(result)) {
                const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(2);
                while(running && std::chrono::steady_clock::now()<deadline) {
                    events(); std::this_thread::sleep_for(std::chrono::milliseconds(10));
                }
            }
        }
    }
    void cleanup() {
        if(env && cameraRelease) { env->CallVoidMethod(activity,cameraRelease); if(env->ExceptionCheck()) env->ExceptionClear(); }
        if(cameraTransformArray) { env->DeleteLocalRef(cameraTransformArray); cameraTransformArray=nullptr; }
        if(context!=EGL_NO_CONTEXT) {
            for(auto &eye:eyes) { glDeleteFramebuffers(1,&eye.fbo); glDeleteRenderbuffers(1,&eye.depth); }
            glDeleteProgram(sceneProgram); glDeleteProgram(cameraProgram); glDeleteBuffers(1,&vbo);
            glDeleteVertexArrays(1,&vao); glDeleteTextures(1,&cameraTexture);
        }
        for(auto &eye:eyes) if(eye.swapchain) xrDestroySwapchain(eye.swapchain);
        if(head) xrDestroySpace(head); if(local) xrDestroySpace(local); if(base) xrDestroySpace(base);
        if(session) xrDestroySession(session); if(instance) xrDestroyInstance(instance);
        if(display!=EGL_NO_DISPLAY) {
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
            if(surface!=EGL_NO_SURFACE) eglDestroySurface(display,surface);
            if(context!=EGL_NO_CONTEXT) eglDestroyContext(display,context);
            eglTerminate(display);
        }
        INFO("session closed frames=%llu",static_cast<unsigned long long>(frames));
    }
};
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_openview_experience_MainActivity_nativeCreate(JNIEnv *env,jobject activity) {
    auto *app=new Experience; app->activity=env->NewGlobalRef(activity); env->GetJavaVM(&app->vm);
    return reinterpret_cast<jlong>(app);
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_openview_experience_MainActivity_nativeRun(JNIEnv *env,jobject,jlong handle) {
    auto *app=reinterpret_cast<Experience*>(handle); app->env=env;
    std::string error;
    try { app->run(); } catch(const std::exception &e) { error=e.what(); ERROR("%s",e.what()); }
    app->cleanup(); return error.empty()?nullptr:env->NewStringUTF(error.c_str());
}
extern "C" JNIEXPORT void JNICALL Java_dev_openview_experience_MainActivity_nativeStop(JNIEnv*,jobject,jlong handle) {
    reinterpret_cast<Experience*>(handle)->stop=true;
}
extern "C" JNIEXPORT void JNICALL Java_dev_openview_experience_MainActivity_nativeRecenter(JNIEnv*,jobject,jlong handle) {
    reinterpret_cast<Experience*>(handle)->recenter=true;
}
extern "C" JNIEXPORT void JNICALL Java_dev_openview_experience_MainActivity_nativeCamera(JNIEnv*,jobject,jlong handle,jboolean value) {
    reinterpret_cast<Experience*>(handle)->cameraWanted=value;
}
extern "C" JNIEXPORT void JNICALL Java_dev_openview_experience_MainActivity_nativeDestroy(JNIEnv *env,jobject,jlong handle) {
    auto *app=reinterpret_cast<Experience*>(handle); env->DeleteGlobalRef(app->activity); delete app;
}
