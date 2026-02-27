package com.bilpark.backend.model;

public enum StreetLocation {
    TEVFIK_BEY("Tevfik Bey Caddesi"),
    ALI_RIZA_OZKAY("Ali Rıza Özkay Caddesi"),
    CUMHURIYET("Cumhuriyet Caddesi");

    private final String displayName;

    //Constructer
    StreetLocation(String displayName){
        this.displayName=displayName;
    }

    public String getDisplayName(){
        return displayName;
    }
}
