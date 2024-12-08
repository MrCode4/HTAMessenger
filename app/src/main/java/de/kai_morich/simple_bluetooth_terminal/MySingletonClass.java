package de.kai_morich.simple_bluetooth_terminal;

public class MySingletonClass {

    private static MySingletonClass instance;

    public static MySingletonClass getInstance() {
        if (instance == null)
            instance = new MySingletonClass();
        return instance;
    }

    private MySingletonClass() {
    }

    private String val;
    private int airDataRate;
    private boolean e22Change = false ;

    public String getValue() {
        return val;
    }
    public int getairDataRate() {
        return airDataRate;
    }

    public void setValue(String value) {
        this.val = value;
    }
    public void setAirDataRate(int value) {
        this.airDataRate = value;
    }
    public void setE22change(boolean bool) {
        this.e22Change = bool;
    }
    public boolean gete22Change() {
        return e22Change;
    }
}
