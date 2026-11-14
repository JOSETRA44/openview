// Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
#include "../src/main/cpp/gaze_interaction.h"
#include <cassert>
#include <iostream>

int main() {
    using namespace openview;
    const GazeVector origin{0,0,0}, forward{0,0,-1};
    assert(std::abs(raySphere(origin,forward,{0,0,-3},.5f)-2.5f)<1e-6f);
    assert(std::isinf(raySphere(origin,forward,{2,0,-3},.5f)));
    assert(std::isinf(raySphere(origin,forward,{0,0,3},.5f)));
    assert(std::abs(raySphere(origin,{0,0,-2},{0,0,-3},.5f)-2.5f)<1e-6f);
    assert(std::abs(raySphere(origin,forward,origin,.5f)-.5f)<1e-6f);
    assert(std::isinf(raySphere(origin,{0,0,0},{0,0,-3},.5f)));
    // A translated viewer still hits the same world object by its actual ray.
    assert(std::abs(raySphere({1,0,0},forward,{1,0,-3},.5f)-2.5f)<1e-6f);
    GazeDwell dwell;
    assert(!dwell.update(1,10));
    assert(!dwell.update(1,11));
    assert(dwell.progress>.79f && dwell.progress<.81f);
    assert(dwell.update(1,11.25));
    assert(!dwell.update(1,30)); // no repeated toggles while looking at it
    assert(!dwell.update(-1,31,true)); // empty space cannot activate
    assert(!dwell.update(1,32));
    assert(dwell.update(1,32.1,true));
    assert(!dwell.update(2,33)); // changing object starts a new timer
    assert(!dwell.update(2,34));
    assert(dwell.update(2,34.25));
    dwell.reset(); // pause/tracking loss must not carry progress across sessions
    assert(!dwell.update(2,40));
    assert(!dwell.update(2,1)); // clock discontinuity rearms, never auto-activates
    assert(!dwell.update(2,std::numeric_limits<double>::quiet_NaN()));
    assert(dwell.target==-1);
    std::cout << "gaze intersection and dwell tests passed\n";
}
