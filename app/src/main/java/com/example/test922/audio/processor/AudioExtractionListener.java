package com.example.test922.audio.processor;

import java.io.File;

public interface AudioExtractionListener {
    void onExtractionStarted();
    void onExtractionSuccess(File audioFile);
    void onExtractionFailure(String errorMessage);
}

