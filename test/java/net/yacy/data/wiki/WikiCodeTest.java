package net.yacy.data.wiki;

import java.io.BufferedReader;
import org.junit.Test;
import static org.junit.Assert.*;


public class WikiCodeTest {

    /**
     * test geo location metadata convert
     */
    @Test
    public void testProcessMetadata() {
        String[] testmeta = new String[]{
            "{{coordinate|NS=52.205944|EW=0.117593|region=GB-CAM|type=landmark}}",  // decimal  N-E location
            "{{coordinate|NS=43/50/29/N|EW=73/23/17/W|type=landmark|region=US-NY}}", // N-W location

            "{{Coordinate |text=DMS |NS=50/7/49/N |EW=6/8/09/E |type=landmark |region=BE-WLG |name=Monument des trois Frontières}}",
            "{{Coordinate |text=DMS |NS= 49.047169|EW=7.899148|region=DE-RP |type=landmark |name=Europadenkmal (Rheinland-Pfalz)}}",

            "{{coordinate|NS=0.00000|EW=0.117593}}", // testing equator coord
            "{{coordinate|NS=-10.00000|EW=-10.10000}}" // testing S-E location

        };
        WikiCode wc = new WikiCode();
        for (int i = 0; i < testmeta.length; i++) {
            String result = wc.transform("http://wiki:8080",testmeta[i]);
            System.out.println(testmeta[i] + " --> " + result);
            // simply check if replacement took place, if no coordinate recognized original string is just html encoded
            assertFalse(result.contains("#124;")); // simple check - result not containing char code for "{",
            assertFalse(result.contains("#125;")); // simple check - result not containing char code for "}"
        }
    }

    /**
     * test header wiki markup
     */
    @Test
    public void testProcessLineOfWikiCode() {
        String[] hdrTeststr = new String[]{ // ok test header
            "== Header ==", "==Header=="};

        String[] nohdrTeststr = new String[]{ // wrong test header
            "Text of = Header, false = wrong", "One=Two"};

        WikiCode wc = new WikiCode();

        for (String s : hdrTeststr) { // test ok header
            String erg = wc.transform("8090", s);
            assertTrue("<h2> tag expected:"+erg, erg.contains("<h2>"));
        }
        for (String s : nohdrTeststr) { // test wrong header
            String erg = wc.transform("8090", s);
            assertFalse("no header tag expected:"+erg, erg.contains("<h1>"));
        }
    }
}
