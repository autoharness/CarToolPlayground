package org.autoharness.cartoolplayground.ui.property

import android.car.VehiclePropertyIds
import android.util.Log
import java.lang.reflect.Modifier

object VehiclePropertyIdsMapper {
    private const val TAG = "VehiclePropertyIdsMapper"

    private val nameToIdMap: Map<String, Int> by lazy {
        try {
            VehiclePropertyIds::class.java.fields.mapNotNull { field ->
                if (Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType) {
                    field.name to field.getInt(null)
                } else {
                    null
                }
            }.toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build VehiclePropertyIds map", e)
            emptyMap()
        }
    }

    /**
     * Converts a property name string (e.g., "INFO_VIN") to its corresponding integer ID.
     * @param name The string name of the property.
     * @return The integer ID, or null if not found.
     */
    fun toId(name: String): Int? = nameToIdMap[name]
}
