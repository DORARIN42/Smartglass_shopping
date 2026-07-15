package com.example.raybanvision.mock

import android.content.Context

/**
 * release 빌드용 no-op 스텁. Mock Device Kit(`mwdat-mockdevice`)은 debugImplementation 이므로
 * release 클래스패스에는 존재하지 않는다. debug 소스셋의 동명 구현이 실제 배선을 담당한다.
 */
object MockGlassesInitializer {
    fun enable(context: Context) = Unit
    fun pairMockGlasses(context: Context) = Unit
}
