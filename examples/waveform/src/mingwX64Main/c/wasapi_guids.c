/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/*
 * Windows GUID definitions for the mingwX64 binary, compiled into its link by
 * build.gradle.kts of this module.
 *
 * audio-io-kmp's WASAPI bindings reference CLSID_MMDeviceEnumerator,
 * IID_IMMDeviceEnumerator, IID_IAudioClient, IID_IAudioCaptureClient,
 * IID_IAudioRenderClient and PKEY_Device_FriendlyName. An MSVC build gets those
 * from uuid.lib; with MinGW none of the 857 import libraries of Kotlin/Native's
 * sysroot defines them - libuuid.a does not carry them, and the sysroot has no
 * libmmdevapi.a - so the link stopped with "undefined symbol:
 * CLSID_MMDeviceEnumerator" and five more of the same.
 *
 * Including <initguid.h> before the headers turns their DEFINE_GUID and
 * DEFINE_PROPERTYKEY declarations into definitions in this translation unit,
 * which is what a build without the SDK's uuid.lib does instead of linking it.
 * The values are the ones the Windows SDK defines, because the declarations
 * they are generated from are the same ones MinGW ships.
 *
 * The HID functions the WASAPI device lookup calls (HidD_GetHidGuid,
 * HidD_GetAttributes, HidD_GetPreparsedData, HidD_FreePreparsedData and
 * HidP_GetCaps) do have an import library in that sysroot; the bindings simply
 * do not link it, which build.gradle.kts does with -lhid.
 */

#include <initguid.h>

#include <audioclient.h>
#include <functiondiscoverykeys_devpkey.h>
#include <mmdeviceapi.h>
