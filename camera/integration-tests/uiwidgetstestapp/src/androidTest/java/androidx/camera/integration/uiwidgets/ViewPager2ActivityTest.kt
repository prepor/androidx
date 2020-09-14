/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.camera.integration.uiwidgets

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.testing.CameraUtil
import androidx.camera.testing.CoreAppTestUtil
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle.State
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(Parameterized::class)
@LargeTest
class ViewPager2ActivityTest(private val lensFacing: Int) {

    companion object {
        private const val ACTION_IDLE_TIMEOUT: Long = 5000
        @JvmStatic
        @Parameterized.Parameters(name = "lensFacing={0}")
        fun data() = listOf(CameraSelector.LENS_FACING_FRONT,
            CameraSelector.LENS_FACING_BACK)
    }

    @get:Rule
    val mUseCamera: TestRule = CameraUtil.grantCameraPermissionAndPreTest()

    private val mDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUp() {
        Assume.assumeTrue(CameraUtil.hasCameraWithLensFacing(lensFacing))

        // Clear the device UI before start each test.
        CoreAppTestUtil.clearDeviceUI(InstrumentationRegistry.getInstrumentation())
    }

    // The test makes sure the camera PreviewView is in the streaming state.
    @Test
    fun testPreviewViewUpdateAfterStopResume() {
        launchActivity(lensFacing).use { scenario ->
            // At first, check Preview in stream state
            assertStreamState(scenario, PreviewView.StreamState.STREAMING)

            // Go through Stop/Resume and then check Preview in stream state still
            scenario.moveToState(State.CREATED)
            scenario.moveToState(State.RESUMED)

            assertStreamState(scenario, PreviewView.StreamState.STREAMING)
            // Make sure the surface texture of TextureView continues getting updates.
            assertSurfaceTextureFramesUpdate(scenario)
        }
    }

    // The test makes sure the TextureView surface texture keeps the same after swipe out/in.
    @Test
    fun testPreviewViewUpdateAfterSwipeOutIn() {

        launchActivity(lensFacing).use { scenario ->
            // At first, check Preview in stream state
            assertStreamState(scenario, PreviewView.StreamState.STREAMING)

            // swipe out CameraFragment and then swipe in to check Preview update
            onView(withId(R.id.viewPager2)).perform(swipeLeft())
            onView(withId(R.id.blank_textview)).check(matches(isDisplayed()))

            onView(withId(R.id.viewPager2)).perform(swipeRight())

            // For b/149877652, need to check if the surface texture of TextureView continues
            // getting updates after detaching from window and then attaching to window.
            assertSurfaceTextureFramesUpdate(scenario)
        }
    }

    @Test
    fun testPreviewViewUpdateAfterSwipeOutAndStop_ResumeAndSwipeIn() {
        launchActivity(lensFacing).use { scenario ->
            // At first, check Preview in stream state
            assertStreamState(scenario, PreviewView.StreamState.STREAMING)

            // swipe out CameraFragment and then Stop and Resume ViewPager2Activity
            onView(withId(R.id.viewPager2)).perform(swipeLeft())
            onView(withId(R.id.blank_textview)).check(matches(isDisplayed()))

            scenario.moveToState(State.CREATED)
            scenario.moveToState(State.RESUMED)
            mDevice.waitForIdle(ACTION_IDLE_TIMEOUT)

            // After resume, swipe in CameraFragment to check Preview in stream state
            onView(withId(R.id.viewPager2)).perform(swipeRight())
            assertStreamState(scenario, PreviewView.StreamState.STREAMING)

            // The test covers pause/resume and ViewPager2 swipe out/in behaviors. Hence, need to
            // check the surface texture of TextureView continues getting updates for b/149877652.
            assertSurfaceTextureFramesUpdate(scenario)
        }
    }

    private fun launchActivity(lensFacing: Int):
            ActivityScenario<ViewPager2Activity> {
        val intent = Intent(
            ApplicationProvider.getApplicationContext<Context>(),
            ViewPager2Activity::class.java
        )
        intent.putExtra(BaseActivity.INTENT_LENS_FACING, lensFacing)
        return ActivityScenario.launch<ViewPager2Activity>(intent)
    }

    private fun getTextureView(previewView: PreviewView): TextureView? {
        var index: Int = 0
        var textureView: TextureView? = null
        lateinit var childView: View

        while (index < previewView.childCount) {
            childView = previewView.getChildAt(index)
            if (childView is TextureView) {
                textureView = childView
                break
            }
            index++
        }
        return textureView
    }

    private fun assertStreamState(
        scenario: ActivityScenario<ViewPager2Activity>,
        expectStreamState: PreviewView.StreamState
    ) = runBlocking<Unit> {
        lateinit var result: Deferred<Boolean>

        scenario.onActivity { activity ->
            // Make async Coroutine to wait the result, not block the test thread.
            result = async { activity.waitForStreamState(expectStreamState) }
        }

        assertThat(result.await()).isTrue()
    }

    private fun assertSurfaceTextureFramesUpdate(scenario: ActivityScenario<ViewPager2Activity>) {
        var newSurfaceTexture: SurfaceTexture? = null
        lateinit var previewView: PreviewView

        scenario.onActivity { activity ->
            previewView = activity.findViewById(R.id.preview_textureview)
            newSurfaceTexture = getTextureView(previewView)!!.surfaceTexture
        }

        val latchForFrameUpdate = CountDownLatch(1)
        newSurfaceTexture!!.setOnFrameAvailableListener { _ ->
            latchForFrameUpdate.countDown()
        }
        assertThat(latchForFrameUpdate.await(ACTION_IDLE_TIMEOUT, TimeUnit.MILLISECONDS)).isTrue()
    }
}
