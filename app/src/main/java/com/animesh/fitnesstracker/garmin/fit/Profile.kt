package com.animesh.fitnesstracker.garmin.fit

/** How a decoded field value is post-processed before it lands in [RawRecord.fields]. */
internal enum class FieldKind {
    /** Plain number: `raw / scale - offset`, a Long when no scale or offset applies, else a Double. */
    NUMBER,
    /** Seconds since the Garmin epoch, converted to Unix seconds. */
    TIMESTAMP,
    /** Local-time seconds since the Garmin epoch (the watch's wall clock), converted like a timestamp. */
    LOCAL_TIMESTAMP,
    /** Semicircles, converted to degrees. */
    COORDINATE,
    /** Null-terminated UTF-8 text. */
    STRING
}

/**
 * One field of the FIT profile: its number inside the message, human name, scale, offset and unit
 * as in Garmin's Profile.xlsx (value = raw / scale - offset). Arrays are recognised from the
 * definition record, not from the profile, so a spec covers both scalar and array use.
 */
internal data class FieldSpec(
    val num: Int,
    val name: String,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val unit: String? = null,
    val kind: FieldKind = FieldKind.NUMBER
) {
    val isScaled: Boolean get() = scale != 1.0 || offset != 0.0
}

/** One global message of the FIT profile with its known fields indexed by field number. */
internal class MessageSpec(val num: Int, val name: String, fields: List<FieldSpec>) {
    val fields: Map<Int, FieldSpec> = fields.associateBy { it.num }
}

/** Global message numbers used by the decoder and mapper, named for readability. */
internal object Mesg {
    const val FILE_ID = 0
    const val CAPABILITIES = 1
    const val USER_PROFILE = 3
    const val SPORT = 12
    const val SESSION = 18
    const val LAP = 19
    const val RECORD = 20
    const val EVENT = 21
    const val WORKOUT = 26
    const val WORKOUT_STEP = 27
    const val ACTIVITY = 34
    const val MONITORING = 55
    const val MONITORING_INFO = 103
    const val DEVICE_STATUS = 104
    const val PHYSIOLOGICAL_METRICS = 140
    const val FIELD_DESCRIPTION = 206
    const val DEVELOPER_DATA_ID = 207
    const val MONITORING_HR_DATA = 211
    const val TIME_IN_ZONE = 216
    const val SET = 225
    const val STRESS_LEVEL = 227
    const val MAX_MET_DATA = 229
    const val SPO2_DATA = 269
    const val SLEEP_LEVEL = 275
    const val EXERCISE_TITLE = 264
    const val METRIC_RECOVERY = 284
    const val RESPIRATION_RATE = 297
    const val RACE_PREDICTION = 339
    const val SLEEP_ASSESSMENT = 346
    const val FUNCTIONAL_METRICS = 356
    const val TRAINING_READINESS = 369
    const val HRV_STATUS_SUMMARY = 370
    const val HRV_VALUE = 371
    const val TRAINING_LOAD = 378
    const val SLEEP_RESTLESS_MOMENTS = 382
    const val HILL_SCORE = 402
    const val ENDURANCE_SCORE = 403
    const val NAP = 412
}

/**
 * Hand-maintained subset of the FIT profile: only the messages the [DecodedFit] contract needs,
 * plus the two developer-data messages every decoder must understand. Numbers, scales, offsets
 * and units follow Garmin's public profile and the Gadgetbridge `fit_profile.json` for the
 * undocumented wellness messages. Adding a message is one `msg(...)` entry in [messages].
 *
 * Field 253 (timestamp) and 254 (message_index) mean the same in every message and are resolved
 * for unknown messages too.
 */
internal object Profile {
    private const val S = "s"
    private const val M = "m"
    private const val BPM = "bpm"
    private const val KCAL = "kcal"
    private const val MS = "ms"
    private const val PCT = "%"
    private const val MPS = "m/s"
    private const val MIN = "min"
    private const val RPM = "rpm"
    private const val W = "W"
    private const val C = "C"
    private const val MM = "mm"

    private fun f(num: Int, name: String, scale: Double = 1.0, offset: Double = 0.0, unit: String? = null) =
        FieldSpec(num, name, scale, offset, unit)

    private fun ts(num: Int = 253, name: String = "timestamp") = FieldSpec(num, name, unit = S, kind = FieldKind.TIMESTAMP)
    private fun local(num: Int, name: String = "local_timestamp") = FieldSpec(num, name, unit = S, kind = FieldKind.LOCAL_TIMESTAMP)
    private fun coord(num: Int, name: String) = FieldSpec(num, name, unit = "deg", kind = FieldKind.COORDINATE)
    private fun str(num: Int, name: String) = FieldSpec(num, name, kind = FieldKind.STRING)
    private fun idx() = f(254, "message_index")
    private fun alt(num: Int, name: String) = f(num, name, 5.0, 500.0, M)
    private fun msg(num: Int, name: String, vararg fields: FieldSpec) = MessageSpec(num, name, fields.toList())

    private val timestampField = ts()
    private val messageIndexField = idx()

    val messages: Map<Int, MessageSpec> = listOf(
        msg(
            Mesg.FILE_ID, "file_id",
            f(0, "type"), f(1, "manufacturer"), f(2, "product"), f(3, "serial_number"), ts(4, "time_created"),
            f(5, "number"), f(6, "manufacturer_partner"), ts(7, "original_time_created"), str(8, "product_name")
        ),
        msg(
            Mesg.CAPABILITIES, "capabilities",
            f(0, "languages"), f(1, "sports"), f(21, "workouts_supported"), f(23, "connectivity_supported"),
            f(24, "wifi"), f(26, "audio_prompts")
        ),
        msg(
            Mesg.USER_PROFILE, "user_profile",
            str(0, "friendly_name"), f(1, "gender"), f(2, "age", unit = "y"), f(3, "height", 100.0, unit = M),
            f(4, "weight", 10.0, unit = "kg"), f(5, "language"), f(6, "elev_setting"), f(7, "weight_setting"),
            f(8, "resting_heart_rate", unit = BPM), f(9, "default_max_running_heart_rate", unit = BPM),
            f(10, "default_max_biking_heart_rate", unit = BPM), f(11, "default_max_heart_rate", unit = BPM),
            f(12, "hr_setting"), f(13, "speed_setting"), f(14, "dist_setting"), f(16, "power_setting"),
            f(17, "activity_class"), f(18, "position_setting"), f(21, "temperature_setting"), f(22, "local_id"),
            f(23, "global_id"), f(24, "year_of_birth", offset = -1900.0), f(28, "wake_time", unit = S),
            f(29, "sleep_time", unit = S), f(30, "height_setting"), f(31, "user_running_step_length", 1000.0, unit = M),
            f(32, "user_walking_step_length", 1000.0, unit = M), f(37, "lactate_threshold_speed", 10.0, unit = "km/h"),
            ts(41, "time_last_lthr_update"), f(44, "birth_day"), f(45, "birth_month"), f(47, "depth_setting"),
            f(49, "dive_count"), f(53, "moderate_activity"), f(54, "vigorous_activity"), f(58, "golf_distance"),
            f(62, "gender_x"), str(67, "user_name"), idx()
        ),
        msg(
            Mesg.SPORT, "sport",
            f(0, "sport"), f(1, "sub_sport"), str(3, "name"), f(5, "active"), f(10, "color"), f(11, "high_contrast")
        ),
        msg(
            Mesg.SESSION, "session",
            f(0, "event"), f(1, "event_type"), ts(2, "start_time"), coord(3, "start_position_lat"),
            coord(4, "start_position_long"), f(5, "sport"), f(6, "sub_sport"), f(7, "total_elapsed_time", 1000.0, unit = S),
            f(8, "total_timer_time", 1000.0, unit = S), f(9, "total_distance", 100.0, unit = M), f(10, "total_cycles", unit = "cycles"),
            f(11, "total_calories", unit = KCAL), f(13, "total_fat_calories", unit = KCAL), f(14, "avg_speed", 1000.0, unit = MPS),
            f(15, "max_speed", 1000.0, unit = MPS), f(16, "avg_heart_rate", unit = BPM), f(17, "max_heart_rate", unit = BPM),
            f(18, "avg_cadence", unit = RPM), f(19, "max_cadence", unit = RPM), f(20, "avg_power", unit = W), f(21, "max_power", unit = W),
            f(22, "total_ascent", unit = M), f(23, "total_descent", unit = M), f(24, "total_training_effect", 10.0),
            f(25, "first_lap_index"), f(26, "num_laps"), f(27, "event_group"), f(28, "trigger"), f(34, "normalized_power", unit = W),
            f(35, "training_stress_score", 10.0), f(36, "intensity_factor", 1000.0), f(44, "pool_length", 100.0, unit = M),
            f(46, "pool_length_unit"), f(47, "num_active_lengths"), f(48, "total_work", unit = "J"), alt(49, "avg_altitude"),
            alt(50, "max_altitude"), f(57, "avg_temperature", unit = C), f(58, "max_temperature", unit = C),
            f(59, "total_moving_time", 1000.0, unit = S), f(64, "min_heart_rate", unit = BPM), f(65, "time_in_hr_zone", 1000.0, unit = S),
            f(69, "avg_lap_time", 1000.0, unit = S), f(70, "best_lap_index"), alt(71, "min_altitude"), f(78, "active_time", 1000.0, unit = S),
            f(79, "avg_swim_cadence", 10.0, unit = "strokes/len"), f(80, "avg_swolf"), f(89, "avg_vertical_oscillation", 10.0, unit = MM),
            f(90, "avg_stance_time_percent", 100.0, unit = PCT), f(91, "avg_stance_time", 10.0, unit = MS),
            f(92, "avg_fractional_cadence", 128.0, unit = RPM), f(93, "max_fractional_cadence", 128.0, unit = RPM),
            f(94, "total_fractional_cycles", 128.0, unit = "cycles"), str(110, "sport_profile_name"), f(111, "sport_index"),
            f(124, "enhanced_avg_speed", 1000.0, unit = MPS), f(125, "enhanced_max_speed", 1000.0, unit = MPS),
            alt(126, "enhanced_avg_altitude"), alt(127, "enhanced_min_altitude"), alt(128, "enhanced_max_altitude"),
            f(132, "avg_vertical_ratio", 100.0, unit = PCT), f(133, "avg_stance_time_balance", 100.0, unit = PCT),
            f(134, "avg_step_length", 10.0, unit = MM), f(137, "total_anaerobic_training_effect", 10.0), f(139, "avg_vam", 1000.0, unit = MPS),
            f(150, "min_temperature", unit = C), f(151, "total_sets"), f(168, "training_load_peak", 65536.0),
            f(169, "enhanced_avg_respiration_rate", 100.0, unit = "brpm"), f(170, "enhanced_max_respiration_rate", 100.0, unit = "brpm"),
            f(178, "estimated_sweat_loss", unit = "ml"), f(179, "fluid_consumed", unit = "ml"), f(180, "enhanced_min_respiration_rate", 100.0, unit = "brpm"),
            f(188, "primary_benefit"), f(189, "start_stress", 100.0), f(190, "end_stress", 100.0), f(192, "workout_feel"), f(193, "workout_rpe"),
            f(194, "avg_spo2", unit = PCT), f(195, "avg_stress", unit = PCT), f(196, "resting_calories", unit = KCAL), f(197, "hrv_sdrr", unit = MS),
            f(198, "hrv_rmssd", unit = MS), f(201, "max_stress"), f(202, "recovery_heart_rate", unit = BPM), f(205, "beginning_potential", unit = PCT),
            f(206, "ending_potential", unit = PCT), f(207, "min_stamina", unit = PCT), f(215, "beginning_body_battery"), f(216, "ending_body_battery"),
            ts(), idx()
        ),
        msg(
            Mesg.LAP, "lap",
            f(0, "event"), f(1, "event_type"), ts(2, "start_time"), coord(3, "start_position_lat"), coord(4, "start_position_long"),
            coord(5, "end_position_lat"), coord(6, "end_position_long"), f(7, "total_elapsed_time", 1000.0, unit = S),
            f(8, "total_timer_time", 1000.0, unit = S), f(9, "total_distance", 100.0, unit = M), f(10, "total_cycles", unit = "cycles"),
            f(11, "total_calories", unit = KCAL), f(12, "total_fat_calories", unit = KCAL), f(13, "avg_speed", 1000.0, unit = MPS),
            f(14, "max_speed", 1000.0, unit = MPS), f(15, "avg_heart_rate", unit = BPM), f(16, "max_heart_rate", unit = BPM),
            f(17, "avg_cadence", unit = RPM), f(18, "max_cadence", unit = RPM), f(19, "avg_power", unit = W), f(20, "max_power", unit = W),
            f(21, "total_ascent", unit = M), f(22, "total_descent", unit = M), f(23, "intensity"), f(24, "lap_trigger"), f(25, "sport"),
            f(26, "event_group"), f(32, "num_lengths"), f(33, "normalized_power", unit = W), f(39, "sub_sport"), f(40, "num_active_lengths"),
            f(41, "total_work", unit = "J"), alt(42, "avg_altitude"), alt(43, "max_altitude"), f(50, "avg_temperature", unit = C),
            f(51, "max_temperature", unit = C), f(52, "total_moving_time", 1000.0, unit = S), f(57, "time_in_hr_zone", 1000.0, unit = S),
            alt(62, "min_altitude"), f(63, "min_heart_rate", unit = BPM), f(70, "active_time", 1000.0, unit = S), f(71, "wkt_step_index"),
            f(73, "avg_swolf"), f(77, "avg_vertical_oscillation", 10.0, unit = MM), f(78, "avg_stance_time_percent", 100.0, unit = PCT),
            f(79, "avg_stance_time", 10.0, unit = MS), f(80, "avg_fractional_cadence", 128.0, unit = RPM),
            f(81, "max_fractional_cadence", 128.0, unit = RPM), f(82, "total_fractional_cycles", 128.0, unit = "cycles"),
            f(110, "enhanced_avg_speed", 1000.0, unit = MPS), f(111, "enhanced_max_speed", 1000.0, unit = MPS),
            alt(112, "enhanced_avg_altitude"), alt(113, "enhanced_min_altitude"), alt(114, "enhanced_max_altitude"),
            f(118, "avg_vertical_ratio", 100.0, unit = PCT), f(119, "avg_stance_time_balance", 100.0, unit = PCT),
            f(120, "avg_step_length", 10.0, unit = MM), f(121, "avg_vam", 1000.0, unit = MPS), f(124, "min_temperature", unit = C),
            f(136, "enhanced_avg_respiration_rate", 100.0, unit = "brpm"), f(137, "enhanced_max_respiration_rate", 100.0, unit = "brpm"),
            f(155, "calories", unit = KCAL), ts(), idx()
        ),
        msg(
            Mesg.RECORD, "record",
            coord(0, "position_lat"), coord(1, "position_long"), alt(2, "altitude"), f(3, "heart_rate", unit = BPM), f(4, "cadence", unit = RPM),
            f(5, "distance", 100.0, unit = M), f(6, "speed", 1000.0, unit = MPS), f(7, "power", unit = W), f(9, "grade", 100.0, unit = PCT),
            f(10, "resistance"), f(13, "temperature", unit = C), f(18, "cycles", unit = "cycles"), f(19, "total_cycles", unit = "cycles"),
            f(29, "accumulated_power", unit = W), f(30, "left_right_balance"), f(31, "gps_accuracy", unit = M),
            f(32, "vertical_speed", 1000.0, unit = MPS), f(33, "calories", unit = KCAL), f(39, "vertical_oscillation", 10.0, unit = MM),
            f(40, "stance_time_percent", 100.0, unit = PCT), f(41, "stance_time", 10.0, unit = MS), f(42, "activity_type"),
            f(53, "fractional_cadence", 128.0, unit = RPM), f(73, "enhanced_speed", 1000.0, unit = MPS), alt(78, "enhanced_altitude"),
            f(83, "vertical_ratio", 100.0, unit = PCT), f(84, "stance_time_balance", 100.0, unit = PCT), f(85, "step_length", 10.0, unit = MM),
            f(90, "performance_condition"), f(91, "absolute_pressure", unit = "Pa"), f(99, "respiration_rate"),
            f(108, "enhanced_respiration_rate", 100.0, unit = "brpm"), f(116, "current_stress", 100.0), f(133, "spo2", unit = PCT),
            f(136, "wrist_heart_rate", unit = BPM), f(137, "stamina_potential", unit = PCT), f(138, "stamina", unit = PCT),
            f(139, "core_temperature", 100.0, unit = C), f(140, "grade_adjusted_speed", 1000.0, unit = MPS), f(143, "body_battery"),
            f(144, "external_heart_rate", unit = BPM), ts()
        ),
        msg(
            Mesg.EVENT, "event",
            f(0, "event"), f(1, "event_type"), f(2, "data16"), f(3, "data"), f(4, "event_group"), f(13, "device_index"),
            f(14, "activity_type"), ts(15, "start_timestamp"), ts()
        ),
        msg(
            Mesg.WORKOUT, "workout",
            f(4, "sport"), f(5, "capabilities"), f(6, "num_valid_steps"), str(8, "wkt_name"), f(11, "sub_sport"),
            f(14, "pool_length", 100.0, unit = M), f(15, "pool_length_unit"), str(17, "notes"), idx()
        ),
        msg(
            Mesg.WORKOUT_STEP, "workout_step",
            str(0, "wkt_step_name"), f(1, "duration_type"), f(2, "duration_value"), f(3, "target_type"), f(4, "target_value"),
            f(5, "custom_target_value_low"), f(6, "custom_target_value_high"), f(7, "intensity"), str(8, "notes"), f(9, "equipment"),
            f(10, "exercise_category"), f(11, "exercise_name"), f(12, "exercise_weight", 100.0, unit = "kg"), f(13, "weight_display_unit"),
            f(19, "secondary_target_type"), f(20, "secondary_target_value"), f(21, "secondary_custom_target_value_low"),
            f(22, "secondary_custom_target_value_high"), idx()
        ),
        msg(
            Mesg.EXERCISE_TITLE, "exercise_title",
            f(0, "exercise_category"), f(1, "exercise_name"), str(2, "wkt_step_name"), idx()
        ),
        msg(
            Mesg.SET, "set",
            f(0, "duration", 1000.0, unit = S), f(3, "repetitions"), f(4, "weight", 16.0, unit = "kg"), f(5, "set_type"),
            ts(6, "start_time"), f(7, "category"), f(8, "category_subtype"), f(9, "weight_display_unit"), f(10, "message_index"),
            f(11, "wkt_step_index"), ts(254, "timestamp")
        ),
        msg(
            Mesg.ACTIVITY, "activity",
            f(0, "total_timer_time", 1000.0, unit = S), f(1, "num_sessions"), f(2, "type"), f(3, "event"), f(4, "event_type"),
            local(5), f(6, "event_group"), str(8, "name"), ts()
        ),
        msg(
            Mesg.MONITORING, "monitoring",
            f(0, "device_index"), f(1, "calories", unit = KCAL), f(2, "distance", 100.0, unit = M), f(3, "cycles", unit = "cycles"),
            f(4, "active_time", 1000.0, unit = S), f(5, "activity_type"), f(6, "activity_subtype"), f(7, "activity_level"),
            f(8, "distance_16", unit = "100 m"), f(9, "cycles_16", unit = "2 cycles"), f(10, "active_time_16", unit = S), local(11),
            f(12, "temperature", 100.0, unit = C), f(14, "temperature_min", 100.0, unit = C), f(15, "temperature_max", 100.0, unit = C),
            f(16, "activity_time", unit = MIN), f(19, "active_calories", unit = KCAL), f(24, "current_activity_type_intensity"),
            f(25, "timestamp_min_8", unit = MIN), f(26, "timestamp_16", unit = S), f(27, "heart_rate", unit = BPM), f(28, "intensity", 10.0),
            f(29, "duration_min", unit = MIN), f(30, "duration", unit = S), f(31, "ascent", 1000.0, unit = M), f(32, "descent", 1000.0, unit = M),
            f(33, "moderate_activity_minutes", unit = MIN), f(34, "vigorous_activity_minutes", unit = MIN),
            f(35, "total_ascent", 1000.0, unit = M), f(36, "total_descent", 1000.0, unit = M), f(37, "moderate_activity", unit = MIN),
            f(38, "vigorous_activity", unit = MIN), ts(), idx()
        ),
        msg(
            Mesg.MONITORING_INFO, "monitoring_info",
            local(0), f(1, "activity_type"), f(3, "steps_to_distance", 5000.0, unit = "m/cycle"), f(4, "steps_to_calories", 5000.0, unit = "kcal/cycle"),
            f(5, "resting_metabolic_rate", unit = "kcal/day"), f(7, "cycles_goal", 2.0, unit = "cycles"), ts(), idx()
        ),
        msg(
            Mesg.DEVICE_STATUS, "device_status",
            f(0, "battery_voltage", 1000.0, unit = "V"), f(2, "battery_level", unit = PCT), f(3, "temperature", unit = C), ts()
        ),
        msg(
            Mesg.PHYSIOLOGICAL_METRICS, "physiological_metrics",
            f(1, "new_hr_max", unit = BPM), f(4, "aerobic_effect", 10.0), f(5, "energy_expenditure", 65536.0, unit = "kcal/min"),
            f(6, "total_energy_expenditure", 65536.0, unit = KCAL), f(7, "met_max", 65536.0), f(8, "met_max_minutes", unit = MIN),
            f(9, "recovery_time", unit = MIN), f(11, "sport"), f(12, "sub_sport"), f(13, "minimal_hr", unit = BPM),
            f(14, "lactate_threshold_heart_rate", unit = BPM), f(15, "lactate_threshold_power", unit = W),
            f(16, "lactate_threshold_speed", 10.0, unit = "km/h"), f(17, "ending_performance_condition"), f(20, "anaerobic_effect", 10.0),
            f(21, "respiratory_rate", 65536.0), f(25, "ending_body_battery"), f(29, "first_vo2_max", 65536.0), f(30, "avg_temperature", unit = C),
            f(32, "avg_altitude", 65536.0, unit = M), f(35, "total_elapsed_time", 1000.0, unit = S), f(36, "total_distance", 100.0, unit = M),
            f(41, "primary_benefit"), local(48), f(50, "ending_potential", unit = PCT), f(60, "total_ascent", unit = M),
            f(61, "total_descent", unit = M), f(62, "average_power", unit = W), f(63, "average_heart_rate", unit = BPM),
            f(64, "user_weight", unit = "kg"), ts()
        ),
        msg(
            Mesg.FIELD_DESCRIPTION, "field_description",
            f(0, "developer_data_index"), f(1, "field_definition_number"), f(2, "fit_base_type_id"), str(3, "field_name"), f(4, "array"),
            str(5, "components"), f(6, "scale"), f(7, "offset"), str(8, "units"), f(14, "native_mesg_num"), f(15, "native_field_num")
        ),
        msg(
            Mesg.DEVELOPER_DATA_ID, "developer_data_id",
            f(0, "developer_id"), f(1, "application_id"), f(2, "manufacturer_id"), f(3, "developer_data_index"), f(4, "application_version")
        ),
        msg(
            Mesg.MONITORING_HR_DATA, "monitoring_hr_data",
            f(0, "resting_heart_rate", unit = BPM), f(1, "current_day_resting_heart_rate", unit = BPM), ts()
        ),
        msg(
            Mesg.TIME_IN_ZONE, "time_in_zone",
            f(0, "reference_mesg"), f(1, "reference_index"), f(2, "time_in_hr_zone", 1000.0, unit = S), f(3, "time_in_speed_zone", 1000.0, unit = S),
            f(4, "time_in_cadence_zone", 1000.0, unit = S), f(5, "time_in_power_zone", 1000.0, unit = S), f(6, "hr_zone_high_boundary", unit = BPM),
            f(7, "speed_zone_high_boundary", 1000.0, unit = MPS), f(8, "cadence_zone_high_boundary", unit = RPM),
            f(9, "power_zone_high_boundary", unit = W), f(10, "hr_calc_type"), f(11, "max_heart_rate", unit = BPM),
            f(12, "resting_heart_rate", unit = BPM), f(13, "threshold_heart_rate", unit = BPM), f(14, "pwr_calc_type"),
            f(15, "functional_threshold_power", unit = W), ts()
        ),
        msg(
            Mesg.STRESS_LEVEL, "stress_level",
            f(0, "stress_level_value"), ts(1, "stress_level_time"), f(2, "average_stress"), f(3, "body_battery")
        ),
        msg(
            Mesg.MAX_MET_DATA, "max_met_data",
            ts(0, "update_time"), f(2, "vo2_max", 10.0, unit = "ml/kg/min"), f(3, "fitness_age", unit = "y"), f(5, "sport"), f(6, "sub_sport"),
            f(8, "max_met_category"), f(9, "calibrated_data"), f(12, "hr_source"), f(13, "speed_source")
        ),
        msg(
            Mesg.SPO2_DATA, "spo2_data",
            f(0, "reading_spo2", unit = PCT), f(1, "reading_confidence"), f(2, "mode"), ts()
        ),
        msg(Mesg.SLEEP_LEVEL, "sleep_level", f(0, "sleep_level"), ts()),
        msg(
            Mesg.METRIC_RECOVERY, "metric_recovery",
            f(0, "recovery_minutes", unit = MIN), ts(1, "recovery_start")
        ),
        msg(Mesg.RESPIRATION_RATE, "respiration_rate", f(0, "respiration_rate", 100.0, unit = "brpm"), ts()),
        msg(
            Mesg.RACE_PREDICTION, "race_prediction",
            local(0), f(1, "time_5k", unit = S), f(2, "time_10k", unit = S), f(3, "time_half_marathon", unit = S),
            f(4, "time_full_marathon", unit = S), ts()
        ),
        msg(
            Mesg.SLEEP_ASSESSMENT, "sleep_assessment",
            f(0, "combined_awake_score"), f(1, "awake_time_score"), f(2, "awakenings_count_score"), f(3, "deep_sleep_score"),
            f(4, "sleep_duration_score"), f(5, "light_sleep_score"), f(6, "overall_sleep_score"), f(7, "sleep_quality_score"),
            f(8, "sleep_recovery_score"), f(9, "rem_sleep_score"), f(10, "sleep_restlessness_score"), f(11, "awakenings_count"),
            f(14, "interruptions_score"), f(15, "average_stress_during_sleep", 100.0)
        ),
        msg(
            Mesg.FUNCTIONAL_METRICS, "functional_metrics",
            f(4, "functional_threshold_power", unit = W), f(7, "running_lactate_threshold_power", unit = W),
            f(8, "running_lactate_threshold_hr", unit = BPM), f(9, "cycling_lactate_threshold_hr", unit = BPM), ts()
        ),
        msg(
            Mesg.TRAINING_READINESS, "training_readiness",
            f(0, "training_readiness"), f(1, "level"), f(4, "sleep_score"), local(20), f(21, "weekly_hrv_average", 128.0, unit = MS),
            f(22, "load_acute"), ts()
        ),
        msg(
            Mesg.HRV_STATUS_SUMMARY, "hrv_status_summary",
            f(0, "weekly_average", 128.0, unit = MS), f(1, "last_night_average", 128.0, unit = MS), f(2, "last_night_5_min_high", 128.0, unit = MS),
            f(3, "baseline_low_upper", 128.0, unit = MS), f(4, "baseline_balanced_lower", 128.0, unit = MS),
            f(5, "baseline_balanced_upper", 128.0, unit = MS), f(6, "status"), ts()
        ),
        msg(Mesg.HRV_VALUE, "hrv_value", f(0, "value", 128.0, unit = MS), ts()),
        msg(
            Mesg.TRAINING_LOAD, "training_load",
            f(3, "training_load_acute"), f(4, "training_load_chronic"), f(5, "daily_acute_chronic_workload_ratio", 10.0), ts()
        ),
        msg(
            Mesg.SLEEP_RESTLESS_MOMENTS, "sleep_restless_moments",
            f(0, "sleep_start", unit = S), f(1, "restless_moments_count"), f(2, "durations")
        ),
        msg(
            Mesg.HILL_SCORE, "hill_score",
            f(0, "hill_score"), f(1, "hill_strength"), f(2, "hill_endurance"), f(3, "trend"), f(4, "level"), ts()
        ),
        msg(
            Mesg.ENDURANCE_SCORE, "endurance_score",
            f(0, "endurance_score"), f(1, "level"), f(3, "lower_bound_intermediate"), f(4, "lower_bound_trained"),
            f(5, "lower_bound_well_trained"), f(6, "lower_bound_expert"), f(7, "lower_bound_superior"), f(8, "lower_bound_elite"),
            f(9, "gauge_lower_limit"), f(10, "gauge_upper_limit"), ts()
        ),
        msg(
            Mesg.NAP, "nap",
            ts(0, "start_timestamp"), f(1, "start_tz_offset", unit = MIN), ts(2, "end_timestamp"), f(3, "end_tz_offset", unit = MIN),
            f(4, "feedback"), f(6, "deleted"), ts(7, "updated_timestamp"), ts(), idx()
        )
    ).associateBy { it.num }

    fun message(num: Int): MessageSpec? = messages[num]

    fun isKnownMessage(num: Int): Boolean = messages.containsKey(num)

    /** The spec for a field, falling back to the universal timestamp and message_index fields. */
    fun field(messageNum: Int, fieldNum: Int): FieldSpec? =
        messages[messageNum]?.fields?.get(fieldNum) ?: universalField(fieldNum)

    /** True when the field is neither in the message's table nor one of the universal fields. */
    fun isUnknownField(messageNum: Int, fieldNum: Int): Boolean =
        messages[messageNum] != null && messages[messageNum]?.fields?.containsKey(fieldNum) != true && universalField(fieldNum) == null

    private fun universalField(fieldNum: Int): FieldSpec? = when (fieldNum) {
        253 -> timestampField
        254 -> messageIndexField
        else -> null
    }
}
