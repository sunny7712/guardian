package com.sunny.guardian.sync;

public interface ConfigReloader {

    /**
     * Called by the central scheduler to fetch and update configuration.
     */
    void reload() throws Exception;
}
