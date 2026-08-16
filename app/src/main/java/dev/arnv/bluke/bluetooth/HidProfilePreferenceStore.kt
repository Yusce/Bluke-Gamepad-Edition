package dev.arnv.bluke.bluetooth

import dev.arnv.bluke.gamepad.HidOutputProfileId

interface StringPreferenceStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class HidProfilePreferenceStore(
    private val storage: StringPreferenceStore
) {
    fun manualSelection(): HidOutputProfileId =
        parse(storage.getString(MANUAL_PROFILE_KEY)) ?: HidOutputProfileId.PC_DIRECT

    fun setManualSelection(profile: HidOutputProfileId) {
        storage.putString(MANUAL_PROFILE_KEY, profile.name)
    }

    fun savedProfile(address: String): HidOutputProfileId? =
        parse(storage.getString(deviceKey(address)))

    fun resolve(address: String, manualSelection: HidOutputProfileId = manualSelection()): HidOutputProfileId =
        savedProfile(address) ?: manualSelection

    fun saveForDevice(address: String, profile: HidOutputProfileId) {
        storage.putString(deviceKey(address), profile.name)
    }

    fun isProfileChoiceConfirmed(address: String): Boolean =
        storage.getString(profileChoiceKey(address)) == PROFILE_CHOICE_CONFIRMED

    fun markProfileChoiceConfirmed(address: String) {
        storage.putString(profileChoiceKey(address), PROFILE_CHOICE_CONFIRMED)
    }

    fun clearForDevice(address: String) {
        storage.remove(deviceKey(address))
        storage.remove(profileChoiceKey(address))
    }

    fun deviceKey(address: String): String = "$DEVICE_PROFILE_PREFIX$address"

    fun profileChoiceKey(address: String): String = "$PROFILE_CHOICE_PREFIX$address"

    private fun parse(value: String?): HidOutputProfileId? =
        value?.let { stored ->
            HidOutputProfileId.entries.firstOrNull { it.name == stored }
        }

    companion object {
        const val DEVICE_PROFILE_PREFIX = "target_profile_"
        const val PROFILE_CHOICE_PREFIX = "gamepad_profile_confirmed_"
        const val PROFILE_CHOICE_CONFIRMED = "true"
        const val MANUAL_PROFILE_KEY = "manual_output_profile"
    }
}
