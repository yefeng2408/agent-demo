package com.yef.agent.graph;

import com.yef.agent.graph.eum.QuestionIntent;

public interface QuestionIntentClassifier {
    QuestionIntent classify(String question);
}