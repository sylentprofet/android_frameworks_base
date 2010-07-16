/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.view;

import android.os.Parcelable;

/**
 * Common base class for input events.
 */
public abstract class InputEvent implements Parcelable {
    protected int mDeviceId;
    protected int mSource;
    
    /*package*/ InputEvent() {
    }

    /**
     * Gets the id for the device that this event came from.  An id of
     * zero indicates that the event didn't come from a physical device
     * and maps to the default keymap.  The other numbers are arbitrary and
     * you shouldn't depend on the values.
     * 
     * @return The device id.
     * @see InputDevice#getDevice
     */
    public final int getDeviceId() {
        return mDeviceId;
    }
    
    /**
     * Gets the device that this event came from.
     * 
     * @return The device, or null if unknown.
     */
    public final InputDevice getDevice() {
        return InputDevice.getDevice(mDeviceId);
    }
    
    /**
     * Gets the source of the event.
     * 
     * @return The event source or {@link InputDevice#SOURCE_UNKNOWN} if unknown.
     * @see InputDevice#getSourceInfo
     */
    public final int getSource() {
        return mSource;
    }
    
    /**
     * Modifies the source of the event.
     * @param source The source.
     * 
     * @hide
     */
    public final void setSource(int source) {
        mSource = source;
    }
}
