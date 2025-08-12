package org.zxkill.nori.skills.weather

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import androidx.datastore.migrations.SharedPreferencesMigration
import kotlinx.coroutines.launch
import org.nori.skill.context.SkillContext
import org.nori.skill.skill.Skill
import org.nori.skill.skill.SkillInfo
import org.zxkill.nori.R
import org.zxkill.nori.settings.ui.ListSetting
import org.zxkill.nori.settings.ui.StringSetting

object WeatherInfo : SkillInfo("weather") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_weather)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_weather)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Cloud)

    override fun isAvailable(ctx: SkillContext): Boolean = true

    override fun build(ctx: SkillContext): Skill<*> {
        return WeatherSkill(this)
    }

    // no need to use Hilt injection here, let DataStore take care of handling the singleton itself
    internal val Context.weatherDataStore by dataStore(
        fileName = "skill_settings_weather.pb",
        serializer = SkillSettingsWeatherSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler {
            SkillSettingsWeatherSerializer.defaultValue
        },
        produceMigrations = { context ->
            listOf(
                SharedPreferencesMigration(
                    context,
                    context.packageName + "_preferences",
                ) { prefs, skillSettingsWeather ->
                    skillSettingsWeather.toBuilder()
                        .setDefaultCity(prefs.getString("weather_default_city"))
                        .build()
                }
            )
        },
    )

    override val renderSettings: @Composable () -> Unit get() = @Composable {
        val dataStore = LocalContext.current.weatherDataStore
        val data by dataStore.data.collectAsState(SkillSettingsWeatherSerializer.defaultValue)
        val scope = rememberCoroutineScope()

        Column {
            StringSetting(
                title = stringResource(R.string.pref_weather_default_city),
                descriptionWhenEmpty = stringResource(R.string.pref_weather_default_city_using_ip_info),
            ).Render(
                value = data.defaultCity,
                onValueChange = { defaultCity ->
                    scope.launch {
                        dataStore.updateData { skillSettingsWeather ->
                            skillSettingsWeather.toBuilder()
                                .setDefaultCity(defaultCity)
                                .build()
                        }
                    }
                },
            )

            ListSetting(
                title = stringResource(R.string.pref_weather_temperature_unit),
                possibleValues = listOf(
                    ListSetting.Value(
                        value = TemperatureUnit.TEMPERATURE_UNIT_SYSTEM,
                        name = stringResource(R.string.use_system_default),
                        // the setting is exposed in system settings only from Android 14
                        // https://developer.android.com/about/versions/14/features#regional-preferences
                        description = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) null
                        else stringResource(R.string.pref_weather_unit_system_instructions),
                    ),
                    ListSetting.Value(
                        value = TemperatureUnit.TEMPERATURE_UNIT_CELSIUS,
                        name = stringResource(R.string.pref_weather_temperature_unit_celsius)
                    ),
                    ListSetting.Value(
                        value = TemperatureUnit.TEMPERATURE_UNIT_FAHRENHEIT,
                        name = stringResource(R.string.pref_weather_temperature_unit_fahrenheit)
                    ),
                    ListSetting.Value(
                        value = TemperatureUnit.TEMPERATURE_UNIT_KELVIN,
                        name = stringResource(R.string.pref_weather_temperature_unit_kelvin)
                    )
                )
            ).Render(
                value = when (val temperatureUnit = data.temperatureUnit) {
                    TemperatureUnit.UNRECOGNIZED -> TemperatureUnit.TEMPERATURE_UNIT_SYSTEM
                    else -> temperatureUnit
                },
                onValueChange = { temperatureUnit ->
                    scope.launch {
                        dataStore.updateData { skillSettingsWeather ->
                            skillSettingsWeather.toBuilder()
                                .setTemperatureUnit(temperatureUnit)
                                .build()
                        }
                    }
                },
            )

            ListSetting(
                title = stringResource(R.string.pref_weather_length_unit),
                // there is a way to read the system setting only from API 28
                // https://developer.android.com/reference/android/icu/util/LocaleData#getMeasurementSystem(android.icu.util.ULocale)
                possibleValues = (if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) listOf() else listOf(
                    ListSetting.Value(
                        value = LengthUnit.LENGTH_UNIT_SYSTEM,
                        name = stringResource(R.string.use_system_default),
                        // the setting is exposed in system settings only from Android 16
                        // https://9to5google.com/2025/02/13/android-16-beta-2-units-of-measurement/
                        description = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) null
                        else stringResource(R.string.pref_weather_unit_system_instructions),
                    ),
                )) + listOf(
                    ListSetting.Value(
                        value = LengthUnit.LENGTH_UNIT_METRIC,
                        name = stringResource(R.string.pref_weather_length_unit_metric)
                    ),
                    ListSetting.Value(
                        value = LengthUnit.LENGTH_UNIT_IMPERIAL,
                        name = stringResource(R.string.pref_weather_length_unit_imperial)
                    )
                )
            ).Render(
                value = when (val lengthUnit = data.lengthUnit) {
                    LengthUnit.UNRECOGNIZED,
                    LengthUnit.LENGTH_UNIT_SYSTEM -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            LengthUnit.LENGTH_UNIT_SYSTEM
                        } else {
                            LengthUnit.LENGTH_UNIT_METRIC
                        }
                    }
                    else -> lengthUnit
                },
                onValueChange = { lengthUnit ->
                    scope.launch {
                        dataStore.updateData { skillSettingsWeather ->
                            skillSettingsWeather.toBuilder()
                                .setLengthUnit(lengthUnit)
                                .build()
                        }
                    }
                },
            )
        }
    }
}
