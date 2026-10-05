package com.lrj.wms.driver.process;

public interface ProcessExecutor {
    ProcessOutcome run(ProcessSpec spec);
}
