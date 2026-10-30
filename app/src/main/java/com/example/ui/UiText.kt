package com.example.ui

/** Small shared translation helper for UI copy. Keep layout and behavior independent of locale. */
fun uiText(language: String, indonesian: String, english: String): String =
    if (language == "en") english else indonesian
