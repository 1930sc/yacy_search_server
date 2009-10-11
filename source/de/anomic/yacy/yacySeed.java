// yacySeed.java
// -------------------------------------
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004
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
//
//
// YACY stands for Yet Another CYberspace
//
// the yacySeed Object is the object that bundles and carries all information about
// a single peer in the yacy space.
// The yacySeed object is carried along peers using a string representation, that can
// be compressed and/or scrambled, depending on the purpose of the process.
//
// the yacy status
// any value that is defined here will be overwritten each time the proxy is started
// to prevent that the system gets confused, it should be set to "" which means
// undefined. Other status' that can be reached at run-time are
// junior    - a peer that has no public socket, thus cannot be reached on demand
// senior    - a peer that has a public socked and serves search queries
// principal - a peer like a senior socket and serves as gateway for network definition

package de.anomic.yacy;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.yacy.kelondro.data.word.Word;
import net.yacy.kelondro.order.Base64Order;
import net.yacy.kelondro.order.Digest;
import net.yacy.kelondro.util.DateFormatter;
import net.yacy.kelondro.util.MapTools;

import de.anomic.net.natLib;
import de.anomic.tools.bitfield;
import de.anomic.tools.crypt;
import de.anomic.yacy.dht.FlatWordPartitionScheme;

public class yacySeed implements Cloneable {

    public static final int maxsize = 4096;
    /**
     * <b>substance</b> "sI" (send index/words)
     */
    public static final String INDEX_OUT = "sI";
    /**
     * <b>substance</b> "rI" (received index/words)
     */
    public static final String INDEX_IN  = "rI";
    /**
     * <b>substance</b> "sU" (send URLs)
     */
    public static final String URL_OUT = "sU";
    /**
     * <b>substance</b> "rU" (received URLs)
     */
    public static final String URL_IN  = "rU";
    /**
     * <b>substance</b> "virgin"
     */
    public static final String PEERTYPE_VIRGIN = "virgin";
    /**
     * <b>substance</b> "junior"
     */
    public static final String PEERTYPE_JUNIOR = "junior";
    /**
     * <b>substance</b> "senior"
     */
    public static final String PEERTYPE_SENIOR = "senior";
    /**
     * <b>substance</b> "principal"
     */
    public static final String PEERTYPE_PRINCIPAL = "principal";
    /**
     * <b>substance</b> "PeerType"
     */
    public static final String PEERTYPE = "PeerType";

    /** static/dynamic (if the IP changes often for any reason) */
    public static final String IPTYPE    = "IPType";
    public static final String FLAGS     = "Flags";
    public static final String FLAGSZERO = "____";
    /** the applications version */
    public static final String VERSION   = "Version";

    public static final String YOURTYPE  = "yourtype";
    public static final String LASTSEEN  = "LastSeen";
    public static final String USPEED    = "USpeed";

    /** the name of the peer (user-set) */
    public static final String NAME      = "Name";
    public static final String HASH      = "Hash";
    /** Birthday - first startup */
    public static final String BDATE     = "BDate";
    /** UTC-Offset */
    public static final String UTC       = "UTC";
    public static final String PEERTAGS  = "Tags";

    /** the speed of indexing (pages/minute) of the peer */
    public static final String ISPEED    = "ISpeed";
    /** the speed of retrieval (queries/minute) of the peer */
    public static final String RSPEED    = "RSpeed";
    /** the number of minutes that the peer is up in minutes/day (moving average MA30) */
    public static final String UPTIME    = "Uptime";
    /** the number of links that the peer has stored (LURL's) */
    public static final String LCOUNT    = "LCount";
    /** the number of links that the peer has noticed, but not loaded (NURL's) */
    public static final String NCOUNT    = "NCount";
    /** the number of links that the peer provides for remote crawls (ZURL's) */
    public static final String RCOUNT    = "RCount";
    /** the number of different words the peer has indexed */
    public static final String ICOUNT    = "ICount";
    /** the number of seeds that the peer has stored */
    public static final String SCOUNT    = "SCount";
    /** the number of clients that the peer connects (connects/hour as double) */
    public static final String CCOUNT    = "CCount";
    /** Citation Rank (Own) - Count */
    public static final String CRWCNT    = "CRWCnt";
    /** Citation Rank (Other) - Count */
    public static final String CRTCNT    = "CRTCnt";
    public static final String IP        = "IP";
    public static final String PORT      = "Port";
    public static final String SEEDLIST  = "seedURL";
    /** zero-value */
    public static final String ZERO      = "0";
    
    private static final int FLAG_DIRECT_CONNECT            = 0;
    private static final int FLAG_ACCEPT_REMOTE_CRAWL       = 1;
    private static final int FLAG_ACCEPT_REMOTE_INDEX       = 2;
    private static final int FLAG_ACCEPT_CITATION_REFERENCE = 3;
    
    public static final String DFLT_NETWORK_UNIT = "freeworld";
    public static final String DFLT_NETWORK_GROUP = "";

    private static final Random random = new Random(System.currentTimeMillis());
    
    // class variables
    /** the peer-hash */
    public String hash;
    /** a set of identity founding values, eg. IP, name of the peer, YaCy-version, ...*/
    private final Map<String, String> dna;
    public int available;
    public int selectscore = -1; // only for debugging
    public String alternativeIP = null;

    public yacySeed(final String theHash, final Map<String, String> theDna) {
        // create a seed with a pre-defined hash map
        assert theHash != null;
        this.hash = theHash;
        this.dna = theDna;
        final String flags = this.dna.get(yacySeed.FLAGS);
        if ((flags == null) || (flags.length() != 4)) { this.dna.put(yacySeed.FLAGS, yacySeed.FLAGSZERO); }
        this.available = 0;
        this.dna.put(yacySeed.NAME, checkPeerName(get(yacySeed.NAME, "&empty;")));
    }

    public yacySeed(final String theHash) {
        this.dna = new HashMap<String, String>();

        // settings that can only be computed by originating peer:
        // at first startup -
        this.hash = theHash; // the hash key of the peer - very important. should be static somehow, even after restart
        this.dna.put(yacySeed.NAME, "&empty;");
        this.dna.put(yacySeed.BDATE, "&empty;");
        this.dna.put(yacySeed.UTC, "+0000");
        // later during operation -
        this.dna.put(yacySeed.ISPEED, yacySeed.ZERO);
        this.dna.put(yacySeed.RSPEED, yacySeed.ZERO);
        this.dna.put(yacySeed.UPTIME, yacySeed.ZERO);
        this.dna.put(yacySeed.LCOUNT, yacySeed.ZERO);
        this.dna.put(yacySeed.NCOUNT, yacySeed.ZERO);
        this.dna.put(yacySeed.RCOUNT, yacySeed.ZERO);
        this.dna.put(yacySeed.ICOUNT, yacySeed.ZERO);
        this.dna.put(yacySeed.SCOUNT, yacySeed.ZERO);
        this.dna.put(yacySeed.CCOUNT, yacySeed.ZERO);
        this.dna.put(yacySeed.VERSION, yacySeed.ZERO);

        // settings that is created during the 'hello' phase - in first contact
        this.dna.put(yacySeed.IP, "");                 // 123.234.345.456
        this.dna.put(yacySeed.PORT, "&empty;");
        this.dna.put(yacySeed.PEERTYPE, yacySeed.PEERTYPE_VIRGIN); // virgin/junior/senior/principal
        this.dna.put(yacySeed.IPTYPE, "&empty;");

        // settings that can only be computed by visiting peer
        this.dna.put(yacySeed.LASTSEEN, DateFormatter.formatShortSecond(new Date(System.currentTimeMillis() - DateFormatter.UTCDiff()))); // for last-seen date
        this.dna.put(yacySeed.USPEED, yacySeed.ZERO);  // the computated uplink speed of the peer

        this.dna.put(yacySeed.CRWCNT, yacySeed.ZERO);
        this.dna.put(yacySeed.CRTCNT, yacySeed.ZERO);

        // settings that are needed to organize the seed round-trip
        this.dna.put(yacySeed.FLAGS, yacySeed.FLAGSZERO);
        setFlagDirectConnect(false);
        setFlagAcceptRemoteCrawl(true);
        setFlagAcceptRemoteIndex(true);
        setFlagAcceptCitationReference(true);
        setUnusedFlags();

        // index transfer
        this.dna.put(yacySeed.INDEX_OUT, yacySeed.ZERO); // send index
        this.dna.put(yacySeed.INDEX_IN, yacySeed.ZERO);  // received index
        this.dna.put(yacySeed.URL_OUT, yacySeed.ZERO);   // send URLs
        this.dna.put(yacySeed.URL_IN, yacySeed.ZERO);    // received URLs

        this.available = 0;
    }
    
    /**
     * check the peer name: protect against usage as XSS hack
     * @param name
     * @return a checked name without "<" and ">"
     */
    private static String checkPeerName(String name) {
        name = name.replaceAll("<", "_");
        name = name.replaceAll(">", "_");
        return name;
    }
    
    /**
     * Checks for the static fragments of a generated default peer name, such as the string 'dpn'
     * @see #makeDefaultPeerName()
     * @param name the peer name to check for default peer name compliance
     * @return whether the given peer name may be a default generated peer name
     */
    public static boolean isDefaultPeerName(final String name) {
        return (name != null &&
                name.length() > 10 &&
                name.charAt(0) <= '9' &&
                name.charAt(name.length() - 1) <= '9' &&
                name.indexOf("dpn") > 0);
    }
    
    /**
     * used when doing routing within a cluster; this can assign a ip and a port
     * that is used instead the address stored in the seed DNA
     */
    public void setAlternativeAddress(final String ipport) {
        if (ipport == null) return;
        final int p = ipport.indexOf(':');
        if (p < 0) this.alternativeIP = ipport; else this.alternativeIP = ipport.substring(0, p);
    }

    /**
     * try to get the IP<br>
     * @return the IP or null
     */
    public final String getIP() {
        String ip = get(yacySeed.IP, "localhost");
        return (ip == null || ip.length() == 0) ? "localhost" : ip;
    }
    /**
     * try to get the peertype<br>
     * @return the peertype or null
     */
    public final String getPeerType() { return get(yacySeed.PEERTYPE, ""); }
    /**
     * try to get the peertype<br>
     * @return the peertype or "virgin"
     */
    public final String orVirgin() { return get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_VIRGIN); }
    /**
     * try to get the peertype<br>
     * @return the peertype or "junior"
     */
    public final String orJunior() { return get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_JUNIOR); }
    /**
     * try to get the peertype<br>
     * @return the peertype or "senior"
     */
    public final String orSenior() { return get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR); }
    /**
     * try to get the peertype<br>
     * @return the peertype or "principal"
     */
    public final String orPrincipal() { return get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_PRINCIPAL); }

    /**
     * Get a value from the peer's DNA (its set of peer defining values, e.g. IP, name, version, ...)
     * @param key the key for the value to fetch
     * @param dflt the default value
     */
    public final String get(final String key, final String dflt) {
        final Object o = this.dna.get(key);
        if (o == null) { return dflt; }
        return (String) o;
    }
    
    public final long getLong(final String key, final long dflt) {
        final Object o = this.dna.get(key);
        if (o == null) { return dflt; }
        if (o instanceof String) try {
        	return Long.parseLong((String) o);
        } catch (final NumberFormatException e) {
        	return dflt;
        } else if (o instanceof Long) {
            return ((Long) o).longValue();
        } else if (o instanceof Integer) {
            return ((Integer) o).intValue();
        } else return dflt;
    }

    public final void setIP(final String ip)     { dna.put(yacySeed.IP, ip); }
    public final void setPort(final String port) { dna.put(yacySeed.PORT, port); }
    public final void setType(final String type) { dna.put(yacySeed.PEERTYPE, type); }
    public final void setJunior()          { dna.put(yacySeed.PEERTYPE, yacySeed.PEERTYPE_JUNIOR); }
    public final void setSenior()          { dna.put(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR); }
    public final void setPrincipal()       { dna.put(yacySeed.PEERTYPE, yacySeed.PEERTYPE_PRINCIPAL); }

    public final void put(final String key, final String value) {
        synchronized (this.dna) {
            this.dna.put(key, value);
        }
    }

    /** @return the DNA-map of this peer */
    public final Map<String, String> getMap() {
        return this.dna;
    }

    public final String getName() {
        return checkPeerName(get(yacySeed.NAME, "&empty;"));
    }

    public final String getHexHash() {
        return b64Hash2hexHash(this.hash);
    }

    public final void incSI(final int count) {
        String v = this.dna.get(yacySeed.INDEX_OUT);
        if (v == null) { v = yacySeed.ZERO; }
        dna.put(yacySeed.INDEX_OUT, Long.toString(Long.parseLong(v) + (long) count));
    }

    public final void incRI(final int count) {
        String v = this.dna.get(yacySeed.INDEX_IN);
        if (v == null) { v = yacySeed.ZERO; }
        dna.put(yacySeed.INDEX_IN, Long.toString(Long.parseLong(v) + (long) count));
    }

    public final void incSU(final int count) {
        String v = this.dna.get(yacySeed.URL_OUT);
        if (v == null) { v = yacySeed.ZERO; }
        dna.put(yacySeed.URL_OUT, Long.toString(Long.parseLong(v) + (long) count));
    }

    public final void incRU(final int count) {
        String v = this.dna.get(yacySeed.URL_IN);
        if (v == null) { v = yacySeed.ZERO; }
        dna.put(yacySeed.URL_IN, Long.toString(Long.parseLong(v) + (long) count));
    }
    
    public final void resetCounters(){
    	dna.put(yacySeed.INDEX_OUT, yacySeed.ZERO);
    	dna.put(yacySeed.INDEX_IN, yacySeed.ZERO);
    	dna.put(yacySeed.URL_OUT, yacySeed.ZERO);
    	dna.put(yacySeed.URL_IN, yacySeed.ZERO);
    }

    /**
     * <code>12 * 6 bit = 72 bit = 24</code> characters octal-hash
     * <p>Octal hashes are used for cache-dumps that are DHT-ready</p>
     * <p>
     *   Cause: the natural order of octal hashes are the same as the b64-order of b64Hashes.
     *   a hexhash cannot be used in such cases, and b64Hashes are not appropriate for file names
     * </p>
     * @param b64Hash a base64 hash
     * @return the octal representation of the given base64 hash
     */
    public static String b64Hash2octalHash(final String b64Hash) {
        return Digest.encodeOctal(Base64Order.enhancedCoder.decode(b64Hash));
    }

    /**
     * <code>12 * 6 bit = 72 bit = 18</code> characters hex-hash
     * @param b64Hash a base64 hash
     * @return the hexadecimal representation of the given base64 hash
     */
    public static String b64Hash2hexHash(final String b64Hash) {
        // the hash string represents 12 * 6 bit = 72 bits. This is too much for a long integer.
        return Digest.encodeHex(Base64Order.enhancedCoder.decode(b64Hash));
    }
    
    /**
     * @param hexHash a hexadecimal hash
     * @return the base64 representation of the given hex hash
     */
    public static String hexHash2b64Hash(final String hexHash) {
        return Base64Order.enhancedCoder.encode(Digest.decodeHex(hexHash));
    }

    /**
     * <code>12 * 6 bit = 72 bit = 9 byte</code>
     * @param b64Hash a base64 hash
     * @return returns a base256 - a byte - representation of the given base64 hash
     */
    public static byte[] b64Hash2b256Hash(final String b64Hash) {
        assert b64Hash.length() == 12;
        return Base64Order.enhancedCoder.decode(b64Hash);
    }
    
    /**
     * @param b256Hash a base256 hash - normal byte number system
     * @return the base64 representation of the given base256 hash
     */
    public static String b256Hash2b64Hash(final byte[] b256Hash) {
        assert b256Hash.length == 9;
        return Base64Order.enhancedCoder.encode(b256Hash);
    }
    
    /**
     * The returned version follows this pattern: <code>MAJORVERSION . MINORVERSION 0 SVN REVISION</code> 
     * @return the YaCy version of this peer as a float or <code>0</code> if no valid value could be retrieved
     * from this yacySeed object
     */
    public final float getVersion() {
        try {
            return Float.parseFloat(get(yacySeed.VERSION, yacySeed.ZERO));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }
    
    /**
     * @return the public address of the peer as IP:port string or <code>null</code> if no valid values for
     * either the IP or the port could be retrieved from this yacySeed object
     */
    public final String getPublicAddress() {
        String ip = this.getIP();
        if (ip == null || ip.length() < 8) ip = "localhost";
        // if (ip.equals(yacyCore.seedDB.mySeed.dna.get(yacySeed.IP))) ip = "127.0.0.1";
        // if (this.hash.equals("xxxxxxxxxxxx")) return "192.168.100.1:3300";
        
        final String port = this.dna.get(yacySeed.PORT);
        if ((port == null) || (port.length() < 2)) return null;

        return ip + ":" + port;
    }
    
    /**
     * If this seed is part of a cluster, the peer has probably the {@linkplain #alternativeIP} object set to
     * a local IP. If this is present and the public IP of this peer is identical to the public IP of the own seed,
     * construct an address using this IP; otherwise return the public address
     * @see #getPublicAddress()
     * @return the alternative IP:port if present, else the public address
     */
    public final String getClusterAddress() {
    	if (this.alternativeIP == null) return getPublicAddress();
    			
        final String port = this.dna.get(yacySeed.PORT);
        if ((port == null) || (port.length() < 2)) return null;

        return this.alternativeIP + ":" + port;
    }
    
    /**
     * @return the IP address of the peer represented by this yacySeed object as {@link InetAddress}
     */
    public final InetAddress getInetAddress() {
        return natLib.getInetAddress(this.getIP());
    }
    
    /** @return the portnumber of this seed or <code>-1</code> if not present */
    public final int getPort() {
        final String port = this.dna.get(yacySeed.PORT);
        if (port == null) return -1;
        /*if (port.length() < 2) return -1; It is possible to use port 0-9*/
        return Integer.parseInt(port);
    }
    
    /**
     * To synchronize peer pings the local time differential must be included in calculations.
     * @return the difference to UTC (universal time coordinated) in milliseconds of this yacySeed,
     * the difference to <code>+0130</code> if not present or <code>0</code> if an error occured during conversion
     */
    public final long getUTCDiff() {
        String utc = this.dna.get(yacySeed.UTC);
        if (utc == null) { utc = "+0130"; }
        try {
            return DateFormatter.UTCDiff(utc);
        } catch (final IllegalArgumentException e) {
            return 0;
        }
    }

    /** puts the current time into the lastseen field and cares about the time differential to UTC */
    public final void setLastSeenUTC() {
        // because java thinks it must apply the UTC offset to the current time,
        // to create a string that looks like our current time, it adds the local UTC offset to the
        // time. To create a corrected UTC Date string, we first subtract the local UTC offset.
        dna.put(yacySeed.LASTSEEN, DateFormatter.formatShortSecond(new Date(System.currentTimeMillis() - DateFormatter.UTCDiff())) );
    }
    
    /**
     * @return the last seen time converted to UTC in milliseconds
     */
    public final long getLastSeenUTC() {
        try {
            final long t = DateFormatter.parseShortSecond(get(yacySeed.LASTSEEN, "20040101000000")).getTime();
            // getTime creates a UTC time number. But in this case java thinks, that the given
            // time string is a local time, which has a local UTC offset applied.
            // Therefore java subtracts the local UTC offset, to get a UTC number.
            // But the given time string is already in UTC time, so the subtraction
            // of the local UTC offset is wrong. We correct this here by adding the local UTC
            // offset again.
            return t + DateFormatter.UTCDiff();
        } catch (final java.text.ParseException e) { // in case of an error make seed look old!!!
            return System.currentTimeMillis() - DateFormatter.dayMillis;
        } catch (final java.lang.NumberFormatException e) {
            return System.currentTimeMillis() - DateFormatter.dayMillis;
        }
    }
    
    /**
     * @see #getLastSeenUTC()
     * @return the last seen value as string representation in the following format: YearMonthDayHoursMinutesSeconds
     * or <code>20040101000000</code> if not present
     */
    public final String getLastSeenString() {
        return get(yacySeed.LASTSEEN, "20040101000000");
    }

    /** @return the age of the seed in number of days */
    public final int getAge() {
        try {
            final long t = DateFormatter.parseShortSecond(get(yacySeed.BDATE, "20040101000000")).getTime();
            return (int) ((System.currentTimeMillis() - (t - getUTCDiff() + DateFormatter.UTCDiff())) / 1000 / 60 / 60 / 24);
        } catch (final java.text.ParseException e) {
            return -1;
        } catch (final java.lang.NumberFormatException e) {
            return -1;
        }
    }

    public void setPeerTags(final Set<String> keys) {
        dna.put(PEERTAGS, MapTools.set2string(keys, "|", false));
    }

    public Set<String> getPeerTags() {
        return MapTools.string2set(get(PEERTAGS, "*"), "|");
    }

    public boolean matchPeerTags(final TreeSet<byte[]> searchHashes) {
        final String peertags = get(PEERTAGS, "");
        if (peertags.equals("*")) return true;
        final Set<String> tags = MapTools.string2set(peertags, "|");
        final Iterator<String> i = tags.iterator();
        while (i.hasNext()) {
        	if (searchHashes.contains(Word.word2hash(i.next()))) return true;
        }
        return false;
    }

    public int getPPM() {
        try {
            return Integer.parseInt(get(yacySeed.ISPEED, yacySeed.ZERO));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    public double getQPM() {
        try {
            return Double.parseDouble(get(yacySeed.RSPEED, yacySeed.ZERO));
        } catch (final NumberFormatException e) {
            return 0d;
        }
    }

    public final long getLinkCount() {
        try {
            return getLong(yacySeed.LCOUNT, 0);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    private boolean getFlag(final int flag) {
        final String flags = get(yacySeed.FLAGS, yacySeed.FLAGSZERO);
        return (new bitfield(flags.getBytes())).get(flag);
    }

    private void setFlag(final int flag, final boolean value) {
        String flags = get(yacySeed.FLAGS, yacySeed.FLAGSZERO);
        if (flags.length() != 4) { flags = yacySeed.FLAGSZERO; }
        final bitfield f = new bitfield(flags.getBytes());
        f.set(flag, value);
        dna.put(yacySeed.FLAGS, new String(f.getBytes()));
    }

    public final void setFlagDirectConnect(final boolean value) { setFlag(FLAG_DIRECT_CONNECT, value); }
    public final void setFlagAcceptRemoteCrawl(final boolean value) { setFlag(FLAG_ACCEPT_REMOTE_CRAWL, value); }
    public final void setFlagAcceptRemoteIndex(final boolean value) { setFlag(FLAG_ACCEPT_REMOTE_INDEX, value); }
    public final void setFlagAcceptCitationReference(final boolean value) { setFlag(FLAG_ACCEPT_CITATION_REFERENCE, value); }
    public final boolean getFlagDirectConnect() { return getFlag(0); }
    public final boolean getFlagAcceptRemoteCrawl() {
        //if (getVersion() < 0.300) return false;
        //if (getVersion() < 0.334) return true;
        return getFlag(1);
    }
    public final boolean getFlagAcceptRemoteIndex() {
        //if (getVersion() < 0.335) return false;
        return getFlag(2);
    }
    public final boolean getFlagAcceptCitationReference() {
        return getFlag(3);
    }
    public final void setUnusedFlags() {
        for (int i = 4; i < 24; i++) { setFlag(i, true); }
    }
    public final boolean isType(final String type) {
        return get(yacySeed.PEERTYPE, "").equals(type);
    }
    public final boolean isVirgin() {
        return get(yacySeed.PEERTYPE, "").equals(yacySeed.PEERTYPE_VIRGIN);
    }
    public final boolean isJunior() {
        return get(yacySeed.PEERTYPE, "").equals(yacySeed.PEERTYPE_JUNIOR);
    }
    public final boolean isSenior() {
        return get(yacySeed.PEERTYPE, "").equals(yacySeed.PEERTYPE_SENIOR);
    }
    public final boolean isPrincipal() {
        return get(yacySeed.PEERTYPE, "").equals(yacySeed.PEERTYPE_PRINCIPAL);
    }
    public final boolean isPotential() {
        return isVirgin() || isJunior();
    }
    public final boolean isActive() {
        return isSenior() || isPrincipal();
    }
    public final boolean isOnline() {
        return isSenior() || isPrincipal();
    }
    public final boolean isOnline(final String type) {
        return type.equals(yacySeed.PEERTYPE_SENIOR) || type.equals(yacySeed.PEERTYPE_PRINCIPAL);
    }

    private static byte[] bestGap(final yacySeedDB seedDB) {
        if ((seedDB == null) || (seedDB.sizeConnected() <= 2)) {
            // use random hash
            return randomHash();
        }
        // find gaps
        final TreeMap<Long, String> gaps = hashGaps(seedDB);
        
        // take one gap; prefer biggest but take also another smaller by chance
        String interval = null;
        final Random r = new Random();
        while (gaps.size() > 0) {
            interval = gaps.remove(gaps.lastKey());
            if (r.nextBoolean()) break;
        }
        if (interval == null) return randomHash();
        
        // find dht position and size of gap
        final long gaphalf = FlatWordPartitionScheme.dhtDistance(
                FlatWordPartitionScheme.std.dhtPosition(interval.substring(0, 12).getBytes(), null),
                FlatWordPartitionScheme.std.dhtPosition(interval.substring(12).getBytes(), null)) >> 1;
        long p = FlatWordPartitionScheme.std.dhtPosition(interval.substring(0, 12).getBytes(), null);
        long gappos = (Long.MAX_VALUE - p >= gaphalf) ? p + gaphalf : (p - Long.MAX_VALUE) + gaphalf;
        return FlatWordPartitionScheme.positionToHash(gappos);
    }
    
    private static TreeMap<Long, String> hashGaps(final yacySeedDB seedDB) {
        final TreeMap<Long, String>gaps = new TreeMap<Long, String>();
        if (seedDB == null) return gaps;
        
        final Iterator<yacySeed> i = seedDB.seedsConnected(true, false, null, (float) 0.0);
        long l;
        yacySeed s0 = null, s1, first = null;
        while (i.hasNext()) {
            s1 = i.next();
            if (s0 == null) {
                s0 = s1;
                first = s0;
                continue;
            }
            l = FlatWordPartitionScheme.dhtDistance(
                    FlatWordPartitionScheme.std.dhtPosition(s0.hash.getBytes(), null),
                    FlatWordPartitionScheme.std.dhtPosition(s1.hash.getBytes(), null));
            gaps.put(l, s0.hash + s1.hash);
            s0 = s1;
        }
        // compute also the last gap
        if ((first != null) && (s0 != null)) {
            l = FlatWordPartitionScheme.dhtDistance(
                    FlatWordPartitionScheme.std.dhtPosition(s0.hash.getBytes(), null),
                    FlatWordPartitionScheme.std.dhtPosition(first.hash.getBytes(), null));
            gaps.put(l, s0.hash + first.hash);
        }
        return gaps;
    }
    
    public static yacySeed genLocalSeed(final yacySeedDB db) {
        return genLocalSeed(db, 0, null); // an anonymous peer
    }
    
    public static yacySeed genLocalSeed(final yacySeedDB db, final int port, final String name) {
        // generate a seed for the local peer
        // this is the birthplace of a seed, that then will start to travel to other peers

        final String hashs = new String(bestGap(db));
        yacyCore.log.logInfo("init: OWN SEED = " + hashs);

        final yacySeed newSeed = new yacySeed(hashs);

        // now calculate other information about the host
        newSeed.dna.put(yacySeed.NAME, (name) == null ? "anonymous" : name);
        newSeed.dna.put(yacySeed.PORT, Integer.toString((port <= 0) ? 8080 : port));
        newSeed.dna.put(yacySeed.BDATE, DateFormatter.formatShortSecond(new Date(System.currentTimeMillis() - DateFormatter.UTCDiff())) );
        newSeed.dna.put(yacySeed.LASTSEEN, newSeed.dna.get(yacySeed.BDATE)); // just as initial setting
        newSeed.dna.put(yacySeed.UTC, DateFormatter.UTCDiffString());
        newSeed.dna.put(yacySeed.PEERTYPE, yacySeed.PEERTYPE_VIRGIN);

        return newSeed;
    }

    //public static String randomHash() { return "zLXFf5lTteUv"; } // only for debugging

    public static byte[] randomHash() {
        final String hash =
            Base64Order.enhancedCoder.encode(Digest.encodeMD5Raw(Long.toString(random.nextLong()))).substring(0, 6) +
            Base64Order.enhancedCoder.encode(Digest.encodeMD5Raw(Long.toString(random.nextLong()))).substring(0, 6);
        return hash.getBytes();
    }

    public static yacySeed genRemoteSeed(final String seedStr, final String key, final boolean ownSeed) {
        // this method is used to convert the external representation of a seed into a seed object
        // yacyCore.log.logFinest("genRemoteSeed: seedStr=" + seedStr + " key=" + key);

        // check protocol and syntax of seed
        if (seedStr == null) { return null; }
        final String seed = crypt.simpleDecode(seedStr, key);
        if (seed == null) { return null; }
        
        // extract hash
        final HashMap<String, String> dna = MapTools.string2map(seed, ",");
        final String hash = dna.remove(yacySeed.HASH);
        if (hash == null) return null;
        final yacySeed resultSeed = new yacySeed(hash, dna);

        // check semantics of content
        final String testResult = resultSeed.isProper(ownSeed);
        if (testResult != null) {
            if (yacyCore.log.isFinest()) yacyCore.log.logFinest("seed is not proper (" + testResult + "): " + resultSeed);
            return null;
        }
        
        // seed ok
        return resultSeed;
    }

    public final String isProper(final boolean checkOwnIP) {
        // checks if everything is ok with that seed
        
        // check hash
        if (this.hash == null) return "hash is null";
        if (this.hash.length() != Word.commonHashLength) return "wrong hash length (" + this.hash.length() + ")";

        // name
        final String peerName = this.dna.get(yacySeed.NAME);
        if (peerName == null) return "no peer name given";
        if (peerName.equalsIgnoreCase("VegaYacyB")) return "bad peer VegaYacyB [ " + this.hash + " ]"; // hack for wrong "VegaYacyB" peers
        dna.put(yacySeed.NAME, checkPeerName(peerName));

        // type
        final String peerType = this.getPeerType();
        if ((peerType == null) || 
            !(peerType.equals(yacySeed.PEERTYPE_VIRGIN) || peerType.equals(yacySeed.PEERTYPE_JUNIOR)
              || peerType.equals(yacySeed.PEERTYPE_SENIOR) || peerType.equals(yacySeed.PEERTYPE_PRINCIPAL)))
            return "invalid peerType '" + peerType + "'";

        // check IP
        if (!checkOwnIP) {
            // checking of IP is omitted if we read the own seed file        
            final String ipCheck = isProperIP(this.getIP());
            if (ipCheck != null) return ipCheck;
        }
        
        // seedURL
        final String seedURL = this.dna.get(SEEDLIST);
        if (seedURL != null && seedURL.length() > 0) {
            if (!seedURL.startsWith("http://") && !seedURL.startsWith("https://")) return "wrong protocol for seedURL";
            try {
                final URL url = new URL(seedURL);
                final String host = url.getHost();
                if (host.equals("localhost") || host.startsWith("127.") || (host.startsWith("0:0:0:0:0:0:0:1"))) return "seedURL in localhost rejected";
            } catch (final MalformedURLException e) {
                return "seedURL malformed";
            }
        }
        return null;
    }
    
    public static final String isProperIP(final String ipString) {
        // returns null if ipString is proper, a string with the cause otervise
        if (ipString == null) return "IP is null";
        if (ipString.length() > 0 && ipString.length() < 8) return "IP is too short: " + ipString;
        if (!natLib.isProper(ipString)) return "IP is not proper: " + ipString; //this does not work with staticIP
        if (ipString.equals("localhost") || ipString.startsWith("127.") || (ipString.startsWith("0:0:0:0:0:0:0:1"))) return "IP for localhost rejected";
        return null;
    }

    public final String toString() {
        HashMap<String, String> copymap = new HashMap<String, String>();
        copymap.putAll(this.dna);
        copymap.put(yacySeed.HASH, this.hash);                // set hash into seed code structure
        return MapTools.map2string(copymap, ",", true); // generate string representation
    }

    public final String genSeedStr(final String key) {
        // use a default encoding
        final String z = this.genSeedStr('z', key);
        final String b = this.genSeedStr('b', key);
        // the compressed string may be longer that the uncompressed if there is too much overhead for compression meta-info
        // take simply that string that is shorter
        if (b.length() < z.length()) return b; else return z;
    }

    public final String genSeedStr(final char method, final String key) {
        return crypt.simpleEncode(this.toString(), key, method);
    }

    public final void save(final File f) throws IOException {
        final String out = this.genSeedStr('p', null);
        final FileWriter fw = new FileWriter(f);
        fw.write(out, 0, out.length());
        fw.close();
    }

    public static yacySeed load(final File f) throws IOException {
        final FileReader fr = new FileReader(f);
        final char[] b = new char[(int) f.length()];
        fr.read(b, 0, b.length);
        fr.close();
        final yacySeed mySeed = genRemoteSeed(new String(b), null, true);
        if (mySeed == null) return null;
        mySeed.dna.put(yacySeed.IP, ""); // set own IP as unknown
        return mySeed;
    }

    @SuppressWarnings("unchecked")
    public final yacySeed clone() {
        synchronized (this.dna) {
            return new yacySeed(this.hash, (HashMap<String, String>) (new HashMap<String, String>(this.dna).clone()));
        }
    }
    
}
