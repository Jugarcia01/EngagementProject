package com.jgo.engagement.application.port.in;

import com.jgo.engagement.domain.model.HeadChange;

/** Application entry point for a state-carrying template-head change. */
public interface ProcessHeadChangeUseCase {
    void process(HeadChange change);
}
