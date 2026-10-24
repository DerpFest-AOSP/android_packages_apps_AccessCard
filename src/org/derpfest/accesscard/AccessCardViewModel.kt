// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class AccessCardViewModel(application: Application) : AndroidViewModel(application) {
    val controller = (application as AccessCardApplication).wallet
    val state = controller.state
}
