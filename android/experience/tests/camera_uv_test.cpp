// Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
// Run on a host: c++ -std=c++17 camera_uv_test.cpp -o camera_uv_test && ./camera_uv_test
#include "../src/main/cpp/camera_uv.h"
#include <cassert>
#include <iostream>
static bool near(float a,float b) { return std::abs(a-b)<.00001f; }
int main() {
    // Fixed expected source corners for target top-left under Android display rotations.
    // At display90, an image's top-right moves to top-left (forward view CCW rotation).
    const float expected[4][2]={{0,1},{1,1},{1,0},{0,0}};
    for(int quarter=0;quarter<4;++quarter) {
        const auto uv=cameraUv(1,1,quarter*90);
        auto point=uv.sample(0,1);
        assert(near(point[0],expected[quarter][0])&&near(point[1],expected[quarter][1]));
        auto center=uv.sample(.5f,.5f);
        assert(near(center[0],.5f)&&near(center[1],.5f));
        // Rotation never mirrors: the oriented basis has positive determinant.
        assert(near(uv.cosine*uv.cosine+uv.sine*uv.sine,1));
    }
    const auto wide=cameraUv(16.f/9,1,0), narrow=cameraUv(9.f/16,1,90);
    assert(near(wide.cropX,9.f/16)&&near(wide.cropY,1));
    assert(near(narrow.cropX,1)&&near(narrow.cropY,9.f/16));
    for(int degrees:{0,90,180,270}) for(float aspect:{9.f/16,1.f,16.f/9}) {
        const auto uv=cameraUv(aspect,1,degrees);
        for(float x:{0.f,1.f}) for(float y:{0.f,1.f}) {
            auto p=uv.sample(x,y);
            assert(p[0]>=-.00001f&&p[0]<=1.00001f&&p[1]>=-.00001f&&p[1]<=1.00001f);
        }
    }
    std::cout << "Camera UV rotation/crop: four orientations passed\n";
}
