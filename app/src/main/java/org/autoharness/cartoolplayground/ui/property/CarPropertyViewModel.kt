package org.autoharness.cartoolplayground.ui.property

import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.hardware.property.Subscription
import android.util.ArraySet
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.autoharness.cartoolplayground.data.property.CarPropertyProfile

data class DisplayProperty(
    val propertyId: Int,
    val areaId: Int,
    val propertyName: String,
    val propertyValue: MutableState<CarPropertyValue<*>?> = mutableStateOf(null),
)

class CarPropertyViewModel : ViewModel() {
    companion object {
        private const val TAG = "CarPropertyViewModel"
    }

    private var carPropertyManager: CarPropertyManager? = null

    private val _displayProperties = MutableStateFlow<List<DisplayProperty>>(emptyList())
    val displayProperties = _displayProperties.asStateFlow()

    private val _propertyUpdateEvent = MutableSharedFlow<DisplayProperty>()
    val propertyUpdateEvent: SharedFlow<DisplayProperty> = _propertyUpdateEvent

    private val carPropertyEventCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(carPropertyValue: CarPropertyValue<*>) {
            Log.v(TAG, "onChangeEvent: $carPropertyValue")
            val displayProperty = _displayProperties.value.find {
                it.propertyId == carPropertyValue.propertyId && it.areaId == carPropertyValue.areaId
            }

            displayProperty?.let { propertyToUpdate ->
                val oldPropertyValue = propertyToUpdate.propertyValue.value
                val hasChanged = oldPropertyValue == null ||
                    !areValuesEqual(carPropertyValue.value, oldPropertyValue.value) ||
                    carPropertyValue.propertyStatus != oldPropertyValue.propertyStatus
                if (hasChanged) {
                    propertyToUpdate.propertyValue.value = carPropertyValue
                    viewModelScope.launch {
                        _propertyUpdateEvent.emit(propertyToUpdate)
                    }
                }
            }
        }

        override fun onErrorEvent(propId: Int, areaId: Int) {
            Log.w(TAG, "onErrorEvent for property $propId in area $areaId")
        }

        fun areValuesEqual(newValue: Any?, oldValue: Any?): Boolean {
            if (newValue === oldValue) return true
            if (newValue == null || oldValue == null) return false
            if (newValue::class != oldValue::class) return false

            return when (newValue) {
                is BooleanArray -> newValue.contentEquals(oldValue as BooleanArray)
                is ByteArray -> newValue.contentEquals(oldValue as ByteArray)
                is CharArray -> newValue.contentEquals(oldValue as CharArray)
                is DoubleArray -> newValue.contentEquals(oldValue as DoubleArray)
                is FloatArray -> newValue.contentEquals(oldValue as FloatArray)
                is IntArray -> newValue.contentEquals(oldValue as IntArray)
                is LongArray -> newValue.contentEquals(oldValue as LongArray)
                is ShortArray -> newValue.contentEquals(oldValue as ShortArray)
                is Array<*> -> newValue.contentDeepEquals(oldValue as Array<*>)
                else -> newValue == oldValue
            }
        }
    }

    /**
     * Initializes the ViewModel, flattens the input profiles, and subscribes to property updates.
     */
    fun initialize(
        carPropertyManagerFlow: Flow<CarPropertyManager?>,
        carPropertyProfilesFlow: Flow<List<CarPropertyProfile>>,
    ) {
        viewModelScope.launch {
            carPropertyManagerFlow
                .combine(carPropertyProfilesFlow) { manager, profiles ->
                    manager to profiles
                }
                .filter { (manager, _) -> manager != null }
                .collect { (manager, profiles) ->
                    Log.i(TAG, "Initializing with $manager and profiles size ${profiles.size}")
                    carPropertyManager?.unsubscribePropertyEvents(carPropertyEventCallback)
                    val propertyManager = manager!!
                    carPropertyManager = propertyManager

                    val validProfilesWithIds = profiles.mapNotNull { profile ->
                        VehiclePropertyIdsMapper.toId(profile.propertyName)?.let { id ->
                            id to profile
                        } ?: run {
                            Log.e(
                                TAG,
                                "Could not find property ID for name: ${profile.propertyName}",
                            )
                            null
                        }
                    }

                    val carPropertyConfigs =
                        propertyManager.getPropertyList(ArraySet(validProfilesWithIds.map { it.first }))
                    val configsById = carPropertyConfigs.associateBy { it.propertyId }

                    val propertiesToDisplay =
                        validProfilesWithIds.flatMap { (propertyId, profile) ->
                            val config = configsById[propertyId]
                            if (config == null) {
                                Log.w(TAG, "Config not found for ${profile.propertyName} ($propertyId)")
                                return@flatMap emptyList<DisplayProperty>()
                            }

                            profile.areaIdProfiles.map { areaProfile ->
                                DisplayProperty(
                                    propertyId = propertyId,
                                    areaId = areaProfile.areaId,
                                    propertyName = profile.propertyName,
                                ).apply {
                                    runCatching {
                                        propertyManager.getProperty(
                                            config.propertyType,
                                            propertyId,
                                            areaProfile.areaId,
                                        )
                                    }.getOrNull()?.let { initialValue ->
                                        propertyValue.value = initialValue
                                    }
                                }
                            }
                        }
                    _displayProperties.value = propertiesToDisplay

                    if (carPropertyConfigs.isNotEmpty()) {
                        val subscriptions = carPropertyConfigs.map { config ->
                            Subscription.Builder(config.propertyId)
                                .setVariableUpdateRateEnabled(true).build()
                        }
                        propertyManager.subscribePropertyEvents(
                            subscriptions,
                            null,
                            carPropertyEventCallback,
                        )
                    }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        carPropertyManager?.unsubscribePropertyEvents(carPropertyEventCallback)
    }
}
