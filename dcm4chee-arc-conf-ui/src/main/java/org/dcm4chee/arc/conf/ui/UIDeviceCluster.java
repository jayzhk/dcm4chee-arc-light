package org.dcm4chee.arc.conf.ui;


/**
 * @author Shefki Esadi <shralsheki@gmail.com>
 * @since May 2018
 */
public class UIDeviceCluster {
    private String clusterName;
    private String description;
    private String[] devices = {};
    private boolean installed = true;


    public UIDeviceCluster() {
    }

    public UIDeviceCluster(String name) {
        this.clusterName = name;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String[] getDevices() {
        return devices;
    }

    public void setDevices(String[] devices) {
        this.devices = devices;
    }

    public boolean isInstalled() {
        return installed;
    }

    public void setInstalled(boolean installed) {
        this.installed = installed;
    }
}
