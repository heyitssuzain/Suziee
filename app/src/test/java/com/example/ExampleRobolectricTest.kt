package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// compileSdk / targetSdk are 36, but Robolectric's SDK 36 sandbox requires a **Java 21**
// runtime and CI builds and tests on JDK 17, so the sandbox is pinned to an SDK level that
// runs everywhere. This only affects the fake Android environment of this example test.
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Suziee", appName)
  }
}
