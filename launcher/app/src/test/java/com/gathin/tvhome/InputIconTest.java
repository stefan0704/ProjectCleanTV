package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class InputIconTest {
    @Test public void consoles() {
        assertEquals(R.drawable.ic_dev_gamepad, InputList.iconFor("PS5"));
        assertEquals(R.drawable.ic_dev_gamepad, InputList.iconFor("Xbox Series X"));
        assertEquals(R.drawable.ic_dev_gamepad, InputList.iconFor("Play-Station 4"));
    }

    @Test public void handhelds() {
        assertEquals(R.drawable.ic_dev_handheld, InputList.iconFor("Steam Deck"));
        assertEquals(R.drawable.ic_dev_handheld, InputList.iconFor("Switch"));
        assertEquals(R.drawable.ic_dev_handheld, InputList.iconFor("ROG Ally"));
    }

    @Test public void computers() {
        assertEquals(R.drawable.ic_dev_desktop, InputList.iconFor("PC"));
        assertEquals(R.drawable.ic_dev_desktop, InputList.iconFor("Ordenador salón"));
        assertEquals(R.drawable.ic_dev_laptop, InputList.iconFor("Portátil"));
        assertEquals(R.drawable.ic_dev_laptop, InputList.iconFor("PC portatil"));
    }

    @Test public void boxesAndDiscs() {
        assertEquals(R.drawable.ic_dev_box, InputList.iconFor("Apple TV"));
        assertEquals(R.drawable.ic_dev_box, InputList.iconFor("Fire-TV"));
        assertEquals(R.drawable.ic_dev_disc, InputList.iconFor("Blu-ray"));
        assertEquals(R.drawable.ic_dev_disc, InputList.iconFor("DVD"));
    }

    @Test public void otherwiseFallback() {
        assertEquals(R.drawable.ic_input, InputList.iconFor("HDMI 1"));
        assertEquals(R.drawable.ic_input, InputList.iconFor(null));
        assertEquals(R.drawable.ic_av, InputList.iconFor("AV", R.drawable.ic_av));
    }
}
