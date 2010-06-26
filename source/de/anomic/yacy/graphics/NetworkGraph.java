// plasmaGrafics.java
// -----------------------
// part of YaCy
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2005
// Created 08.10.2005
//
// Contributions by Marc Nause [MN]
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package de.anomic.yacy.graphics;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Date;
import java.util.Iterator;

import net.yacy.visualization.PrintTool;
import net.yacy.visualization.RasterPlotter;

import de.anomic.search.QueryParams;
import de.anomic.search.SearchEvent;
import de.anomic.search.SearchEventCache;
import de.anomic.search.Switchboard;
import de.anomic.search.SwitchboardConstants;
import de.anomic.yacy.yacySearch;
import de.anomic.yacy.yacySeed;
import de.anomic.yacy.yacySeedDB;
import de.anomic.yacy.dht.FlatWordPartitionScheme;

public class NetworkGraph {

    private static int shortestName = 10;
    private static int longestName = 12;

    public  static final String COL_BACKGROUND     = "FFFFFF";
    private static final String COL_DHTCIRCLE      = "006020";
    private static final String COL_HEADLINE       = "FFFFFF";
    private static final String COL_ACTIVE_DOT     = "000044";
    private static final String COL_ACTIVE_LINE    = "335544";
    private static final String COL_ACTIVE_TEXT    = "66AA88";
    private static final String COL_PASSIVE_DOT    = "221111";
    private static final String COL_PASSIVE_LINE   = "443333";
    private static final String COL_PASSIVE_TEXT   = "663333";
    private static final String COL_POTENTIAL_DOT  = "002200";
    private static final String COL_POTENTIAL_LINE = "224422";
    private static final String COL_POTENTIAL_TEXT = "336633";
    private static final String COL_WE_DOT         = "FF0000";
    private static final String COL_WE_LINE        = "FFAAAA";
    private static final String COL_WE_TEXT        = "FFCCCC";
    
    private static final String COL_BORDER         = "000000";
    private static final String COL_NORMAL_TEXT    = "000000";
    private static final String COL_LOAD_BG        = "F7F7F7";
    
    public static class CircleThreadPiece {
        private final String pieceName;
        private final Color color;
        private long execTime = 0;
        private float fraction = 0;
        
        public CircleThreadPiece(final String pieceName, final Color color) {
            this.pieceName = pieceName;
            this.color = color;
        }
        
        public int getAngle() { return Math.round(360f*this.fraction); }
        public int getFractionPercent() { return Math.round(100f*this.fraction); }
        public Color getColor() { return this.color; }
        public long getExecTime() { return this.execTime; }
        public String getPieceName() { return this.pieceName; }
        
        public void addExecTime(final long execTime) { this.execTime += execTime; }
        public void reset() {
            this.execTime = 0;
            this.fraction = 0;
        }
        public void setExecTime(final long execTime) { this.execTime = execTime; }
        public void setFraction(final long totalBusyTime) {
            this.fraction = (float)this.execTime / (float)totalBusyTime;
        }
    }
    
    private static final int     LEGEND_BOX_SIZE = 10;
    
    private static BufferedImage peerloadPicture = null;
    private static long          peerloadPictureDate = 0;

    private static RasterPlotter bannerPicture = null;      // [MN]
    private static BufferedImage logo = null;               // [MN]
    private static long          bannerPictureDate = 0;     // [MN]

    public static RasterPlotter getSearchEventPicture(final yacySeedDB seedDB, final String eventID, final int coronaangle) {
        final SearchEvent event = SearchEventCache.getEvent(eventID);
        if (event == null) return null;
        final yacySearch[] primarySearches = event.getPrimarySearchThreads();
        final yacySearch[] secondarySearches = event.getSecondarySearchThreads();
        if (primarySearches == null) return null; // this was a local search and there are no threads

        // get a copy of a recent network picture
        final RasterPlotter eventPicture = getNetworkPicture(seedDB, 120000, 640, 480, 300, 300, 1000, coronaangle, Switchboard.getSwitchboard().getConfig(SwitchboardConstants.NETWORK_NAME, "unspecified"), Switchboard.getSwitchboard().getConfig("network.unit.description", "unspecified"), COL_BACKGROUND);
        //if (eventPicture instanceof ymageMatrix) eventPicture = (ymageMatrix) eventPicture; //new ymageMatrix((ymageMatrix) eventPicture);
        // TODO: fix cloning of ymageMatrix pictures
        
        // get dimensions
        final int cr = Math.min(eventPicture.getWidth(), eventPicture.getHeight()) / 5 - 20;
        final int cx = eventPicture.getWidth() / 2;
        final int cy = eventPicture.getHeight() / 2;

        int angle;

        // draw in the primary search peers
        for (int j = 0; j < primarySearches.length; j++) {
            eventPicture.setColor((primarySearches[j].isAlive()) ? RasterPlotter.RED : RasterPlotter.GREEN);
            angle = (int) (360.0 * (((double) FlatWordPartitionScheme.std.dhtPosition(primarySearches[j].target().hash.getBytes(), null)) / ((double) Long.MAX_VALUE)));
            eventPicture.arcLine(cx, cy, cr - 20, cr, angle);
        }

        // draw in the secondary search peers
        if (secondarySearches != null) {
            for (int j = 0; j < secondarySearches.length; j++) {
                eventPicture.setColor((secondarySearches[j].isAlive()) ? RasterPlotter.RED : RasterPlotter.GREEN);
                angle = (int) (360.0 * (((double) FlatWordPartitionScheme.std.dhtPosition(secondarySearches[j].target().hash.getBytes(), null)) / ((double) Long.MAX_VALUE)));
                eventPicture.arcLine(cx, cy, cr - 10, cr, angle - 1);
                eventPicture.arcLine(cx, cy, cr - 10, cr, angle + 1);
            }
        }
        
        // draw in the search target
        final QueryParams query = event.getQuery();
        final Iterator<byte[]> i = query.queryHashes.iterator();
        eventPicture.setColor(RasterPlotter.GREY);
        while (i.hasNext()) {
            long[] positions = seedDB.scheme.dhtPositions(i.next());
            for (int j = 0; j < positions.length; j++) {
                angle = (int) (360.0 * (((double) positions[j]) / ((double) Long.MAX_VALUE)));
                eventPicture.arcLine(cx, cy, cr - 20, cr, angle);
            }
        }

        return eventPicture;
    }

    public static RasterPlotter getNetworkPicture(final yacySeedDB seedDB, final long maxAge, final int width, final int height, final int passiveLimit, final int potentialLimit, final int maxCount, final int coronaangle, final String networkName, final String networkTitle, final String bgcolor) {
        return drawNetworkPicture(seedDB, width, height, passiveLimit, potentialLimit, maxCount, coronaangle, networkName, networkTitle, bgcolor);
    }

    private static RasterPlotter drawNetworkPicture(final yacySeedDB seedDB, final int width, final int height, final int passiveLimit, final int potentialLimit, final int maxCount, final int coronaangle, final String networkName, final String networkTitle, final String bgcolor) {

        RasterPlotter networkPicture = new RasterPlotter(width, height, (bgcolor.equals("000000")) ? RasterPlotter.MODE_ADD : RasterPlotter.MODE_SUB, bgcolor);
        if (seedDB == null) return networkPicture; // no other peers known
        
        final int innerradius = Math.min(width, height) / 5;
        int outerradius = innerradius + innerradius * seedDB.sizeConnected() / 100;
        if (outerradius > innerradius * 2) outerradius = innerradius * 2;

        // draw network circle
        networkPicture.setColor(COL_DHTCIRCLE);
        networkPicture.arc(width / 2, height / 2 + 20, innerradius - 20, innerradius + 20, 0, 360);

        //System.out.println("Seed Maximum distance is       " + yacySeed.maxDHTDistance);
        //System.out.println("Seed Minimum distance is       " + yacySeed.minDHTNumber);

        yacySeed seed;
        long lastseen;

        // draw connected senior and principals
        int count = 0;
        int totalCount = 0;
        Iterator<yacySeed> e = seedDB.seedsConnected(true, false, null, (float) 0.0);
        while (e.hasNext() && count < maxCount) {
            seed = e.next();
            if (seed != null) {
                drawNetworkPicturePeer(networkPicture, width / 2, height / 2 + 20, innerradius, outerradius, seed, COL_ACTIVE_DOT, COL_ACTIVE_LINE, COL_ACTIVE_TEXT, coronaangle);
                count++;
            }
        }
        totalCount += count;

        // draw disconnected senior and principals that have been seen lately
        count = 0;
        e = seedDB.seedsSortedDisconnected(false, yacySeed.LASTSEEN);
        while (e.hasNext() && count < maxCount) {
            seed = e.next();
            if (seed != null) {
                lastseen = Math.abs((System.currentTimeMillis() - seed.getLastSeenUTC()) / 1000 / 60);
                if (lastseen > passiveLimit) break; // we have enough, this list is sorted so we don't miss anything
                drawNetworkPicturePeer(networkPicture, width / 2, height / 2 + 20, innerradius, outerradius, seed, COL_PASSIVE_DOT, COL_PASSIVE_LINE, COL_PASSIVE_TEXT, coronaangle);
                count++;
            }
        }
        totalCount += count;

        // draw juniors that have been seen lately
        count = 0;
        e = seedDB.seedsSortedPotential(false, yacySeed.LASTSEEN);
        while (e.hasNext() && count < maxCount) {
            seed = e.next();
            if (seed != null) {
                lastseen = Math.abs((System.currentTimeMillis() - seed.getLastSeenUTC()) / 1000 / 60);
                if (lastseen > potentialLimit) break; // we have enough, this list is sorted so we don't miss anything
                drawNetworkPicturePeer(networkPicture, width / 2, height / 2 + 20, innerradius, outerradius, seed, COL_POTENTIAL_DOT, COL_POTENTIAL_LINE, COL_POTENTIAL_TEXT, coronaangle);
                count++;
            }
        }
        totalCount += count;

        // draw my own peer
        drawNetworkPicturePeer(networkPicture, width / 2, height / 2 + 20, innerradius, outerradius, seedDB.mySeed(), COL_WE_DOT, COL_WE_LINE, COL_WE_TEXT, coronaangle);

        // draw DHT activity
        
        
        
        
        // draw description
        networkPicture.setColor(COL_HEADLINE);
        PrintTool.print(networkPicture, 2, 8, 0, "YACY NETWORK '" + networkName.toUpperCase() + "'", -1);
        PrintTool.print(networkPicture, 2, 16, 0, networkTitle.toUpperCase(), -1);
        PrintTool.print(networkPicture, width - 2, 8, 0, "SNAPSHOT FROM " + new Date().toString().toUpperCase(), 1);
        PrintTool.print(networkPicture, width - 2, 16, 0, "DRAWING OF " + totalCount + " SELECTED PEERS", 1);
        
        return networkPicture;
    }

    private static void drawNetworkPicturePeer(final RasterPlotter img, final int x, final int y, final int innerradius, final int outerradius, final yacySeed seed, final String colorDot, final String colorLine, final String colorText, final int coronaangle) {
        final String name = seed.getName().toUpperCase() /*+ ":" + seed.hash + ":" + (((double) ((int) (100 * (((double) yacySeed.dhtPosition(seed.hash)) / ((double) yacySeed.maxDHTDistance))))) / 100.0)*/;
        if (name.length() < shortestName) shortestName = name.length();
        if (name.length() > longestName) longestName = name.length();
        final int angle = (int) (360.0 * (((double) FlatWordPartitionScheme.std.dhtPosition(seed.hash.getBytes(), null)) / ((double) Long.MAX_VALUE)));
        //System.out.println("Seed " + seed.hash + " has distance " + seed.dhtDistance() + ", angle = " + angle);
        int linelength = 20 + outerradius * (20 * (name.length() - shortestName) / (longestName - shortestName) + Math.abs(seed.hash.hashCode() % 20)) / 60;
        if (linelength > outerradius) linelength = outerradius;
        int dotsize = 4 + (int) (seed.getLinkCount() / 2000000L);
        if (dotsize > 18) dotsize = 18;
        // draw dot
        img.setColor(colorDot);
        img.arcDot(x, y, innerradius, angle, dotsize);
        // draw line to text
        img.setColor(colorLine);
        img.arcLine(x, y, innerradius + 18, innerradius + linelength, angle);
        // draw text
        img.setColor(colorText);
        PrintTool.arcPrint(img, x, y, innerradius + linelength, angle, name);

        // draw corona around dot for crawling activity
        int ppm20 = seed.getPPM() / 20;
        if (coronaangle >= 0 && ppm20 > 0) {
            double ca = Math.PI * 2.0 * ((double) coronaangle) / 360.0;
            if (ppm20 > 3) ppm20 = 3;
            // draw a wave around crawling peers
            long strength;
            img.setColor("303030");
            img.arcArc(x, y, innerradius, angle, dotsize + 1, dotsize + 1, 0, 360);
            final int waveradius = innerradius / 2;
            for (int r = 0; r < waveradius; r++) {
                strength = (waveradius - r) * (long) (0x08 * ppm20 * (1.0 + Math.sin(ca + Math.PI * 16 * r / waveradius))) / waveradius;
                //System.out.println("r = " + r + ", Strength = " + strength);
                img.setColor((strength << 16) | (strength << 8) | strength);
                img.arcArc(x, y, innerradius, angle, dotsize + r, dotsize + r, 0, 360);
            }
        }
    }
    
    public static BufferedImage getPeerLoadPicture(final long maxAge, final int width, final int height, final CircleThreadPiece[] pieces, final CircleThreadPiece fillRest) {
        if ((peerloadPicture == null) || ((System.currentTimeMillis() - peerloadPictureDate) > maxAge)) {
            drawPeerLoadPicture(width, height, pieces, fillRest);
        }
        return peerloadPicture;
    }
    
    private static void drawPeerLoadPicture(final int width, final int height, final CircleThreadPiece[] pieces, final CircleThreadPiece fillRest) {
    	//prepare image
    	peerloadPicture = new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = peerloadPicture.createGraphics();
        g.setBackground(Color.decode("0x"+COL_LOAD_BG));
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.clearRect(0,0,width,height);
        
        final int circ_w = Math.min(width,height)-20; //width of the circle (r*2)
        final int circ_x = width-circ_w-10;           //x-coordinate of circle-left
        final int circ_y = 10;                        //y-coordinate of circle-top
        int curr_angle = 0;                       //remember current angle
        
        int i;
        for (i=0; i<pieces.length; i++) {
            // draw the piece
            g.setColor(pieces[i].getColor());
            g.fillArc(circ_x, circ_y, circ_w, circ_w, curr_angle, pieces[i].getAngle());
            curr_angle += pieces[i].getAngle();
            
            // draw it's legend line
            drawLegendLine(g, 5, height - 5 - 15 * i, pieces[i].getPieceName()+" ("+pieces[i].getFractionPercent()+" %)", pieces[i].getColor());
        }
        
        // fill the rest
        g.setColor(fillRest.getColor());
        //FIXME: better method to avoid gaps on rounding-differences?
        g.fillArc(circ_x, circ_y, circ_w, circ_w, curr_angle, 360 - curr_angle);
        drawLegendLine(g, 5, height - 5 - 15 * i, fillRest.getPieceName()+" ("+fillRest.getFractionPercent()+" %)", fillRest.getColor());
        
        //draw border around the circle
        g.setColor(Color.decode("0x"+COL_BORDER));
        g.drawArc(circ_x, circ_y, circ_w, circ_w, 0, 360);
        
        peerloadPictureDate = System.currentTimeMillis();
    }
    
    private static void drawLegendLine(final Graphics2D g, final int x, final int y, final String caption, final Color item_color) {
    	g.setColor(item_color);
    	g.fillRect(x, y-LEGEND_BOX_SIZE, LEGEND_BOX_SIZE, LEGEND_BOX_SIZE);
    	g.setColor(Color.decode("0x"+COL_BORDER));
    	g.drawRect(x, y-LEGEND_BOX_SIZE, LEGEND_BOX_SIZE, LEGEND_BOX_SIZE);
    	
    	g.setColor(Color.decode("0x"+COL_NORMAL_TEXT));
    	g.drawChars(caption.toCharArray(), 0, caption.length(), x+LEGEND_BOX_SIZE+5,y);
    }

    //[MN]
    public static RasterPlotter getBannerPicture(final long maxAge, final int width, final int height, final String bgcolor, final String textcolor, final String bordercolor, final String name, final long links, final long words, final String type, final int ppm, final String network, final int peers, final long nlinks, final long nwords, final double nqph, final long nppm) {
        if ((bannerPicture == null) || ((System.currentTimeMillis() - bannerPictureDate) > maxAge)) {
            drawBannerPicture(width, height, bgcolor, textcolor, bordercolor, name, links, words, type, ppm, network, peers, nlinks, nwords, nqph, nppm, logo);
        }
        return bannerPicture;
    }    
    
    //[MN]
    public static RasterPlotter getBannerPicture(final long maxAge, final int width, final int height, final String bgcolor, final String textcolor, final String bordercolor, final String name, final long links, final long words, final String type, final int ppm, final String network, final int peers, final long nlinks, final long nwords, final double nqph, final long nppm, final BufferedImage newLogo) {
        if ((bannerPicture == null) || ((System.currentTimeMillis() - bannerPictureDate) > maxAge)) {
            drawBannerPicture(width, height, bgcolor, textcolor, bordercolor, name, links, words, type, ppm, network, peers, nlinks, nwords, nqph, nppm, newLogo);
        }
        return bannerPicture;
    }

    //[MN]
    private static void drawBannerPicture(final int width, final int height, final String bgcolor, final String textcolor, final String bordercolor, final String name, final long links, final long words, final String type, final int ppm, final String network, final int peers, final long nlinks, final long nwords, final double nqph, final long nppm, final BufferedImage newLogo) {

        final int exprlength = 19;
        logo = newLogo;
        bannerPicture = new RasterPlotter(width, height, RasterPlotter.MODE_REPLACE, bgcolor);

        // draw description
        bannerPicture.setColor(textcolor);
        PrintTool.print(bannerPicture, 100, 12, 0, "PEER:  " + addTrailingBlanks(name, exprlength), -1);
        PrintTool.print(bannerPicture, 100, 22, 0, "LINKS: " + addBlanksAndDots(links, exprlength), -1);
        PrintTool.print(bannerPicture, 100, 32, 0, "WORDS: " + addBlanksAndDots(words, exprlength), -1);
        PrintTool.print(bannerPicture, 100, 42, 0, "TYPE:  " + addTrailingBlanks(type, exprlength), -1);
        PrintTool.print(bannerPicture, 100, 52, 0, "SPEED: " + addTrailingBlanks(ppm + " PAGES/MINUTE", exprlength), -1);

        PrintTool.print(bannerPicture, 285, 12, 0, "NETWORK: " + addTrailingBlanks(network + " [" + peers + "]", exprlength), -1);
        PrintTool.print(bannerPicture, 285, 22, 0, "LINKS:   " + addBlanksAndDots(nlinks, exprlength), -1);
        PrintTool.print(bannerPicture, 285, 32, 0, "WORDS:   " + addBlanksAndDots(nwords, exprlength), -1);
        PrintTool.print(bannerPicture, 285, 42, 0, "QUERIES: " + addTrailingBlanks(nqph + " QUERIES/HOUR", exprlength), -1);
        PrintTool.print(bannerPicture, 285, 52, 0, "SPEED:   " + addTrailingBlanks(nppm + " PAGES/MINUTE", exprlength), -1);

        if (logo != null) {
            final int x = (100/2 - logo.getWidth()/2);
            final int y = (height/2 - logo.getHeight()/2);
            bannerPicture.insertBitmap(logo, x, y, 0, 0, RasterPlotter.FILTER_ANTIALIASING);
        }

        if (!bordercolor.equals("")) {
            bannerPicture.setColor(bordercolor);
            bannerPicture.line(0,0,0,height-1);
            bannerPicture.line(0,0,width-1,0);
            bannerPicture.line(width-1,0,width-1,height-1);
            bannerPicture.line(0,height-1,width-1,height-1);
        }
        
        // set timestamp
         bannerPictureDate = System.currentTimeMillis();
    }
    
    public static boolean logoIsLoaded() {
        if (logo == null) {
            return false;
        }
        return true;
    }

    //[MN]
    private static String addBlanksAndDots(final long input, final int length) {
        return addBlanksAndDots(Long.toString(input), length);
    }

    //[MN]
    private static String addBlanksAndDots(String input, final int length) {
        input = addDots(input);
        input = addTrailingBlanks(input,length);
        return input;
    }

    //[MN]
    private static String addDots(String word) {
        String tmp = "";
        int len = word.length();
        if (len > 3) {
            while(len > 3) {
                if(tmp.equals("")) {
                    tmp = word.substring(len-3,len);
                } else {
                    tmp = word.substring(len-3,len) + "." + tmp;
                }
                word = word.substring(0,len-3);
                len = word.length();
            }
            word = word + "." + tmp;
        }
        return word;
    }

    //[MN]
    private static String addTrailingBlanks(String word, int length) {
        if (length > word.length()) {
            String blanks = "";
            length = length - word.length();
            int i = 0;
            while(i++ < length) {
                blanks += " ";
            }
            word = blanks + word;
        }
        return word;
    }

}