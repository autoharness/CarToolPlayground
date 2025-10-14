package org.autoharness.cartoolplayground

import android.car.Car
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CarConnectionRepository {
    private const val TAG = "CarConnectionRepository"

    private var car: Car? = null
    private val _carPropertyManager = MutableStateFlow<CarPropertyManager?>(null)

    val carPropertyManager: StateFlow<CarPropertyManager?> = _carPropertyManager.asStateFlow()

    private val carServiceLifecycleListener =
        Car.CarServiceLifecycleListener { car, ready ->
            if (ready) {
                _carPropertyManager.value =
                    car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            } else {
                Log.e(TAG, "Car service is killed.")
                _carPropertyManager.value = null
            }
        }

    fun connect(context: Context) {
        if (car?.isConnected == true) {
            Log.i(TAG, "Car is already connected.")
            return
        }
        car = Car.createCar(
            context.applicationContext,
            null,
            Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
            carServiceLifecycleListener,
        )
    }

    fun disconnect() {
        car?.disconnect()
        car = null
        _carPropertyManager.value = null
    }
}
