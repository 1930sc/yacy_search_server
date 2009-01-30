//wikiBoard.java 
//-------------------------------------
//(C) by Michael Peter Christen; mc@yacy.net
//first published on http://www.anomic.de
//Frankfurt, Germany, 2004
//last major change: 20.07.2004

//This program is free software; you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation; either version 2 of the License, or
//(at your option) any later version.

//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//GNU General Public License for more details.

//You should have received a copy of the GNU General Public License
//along with this program; if not, write to the Free Software
//Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package de.anomic.data;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TimeZone;

import de.anomic.kelondro.kelondroBLOBTree;
import de.anomic.kelondro.kelondroMap;
import de.anomic.kelondro.coding.Base64Order;
import de.anomic.kelondro.coding.NaturalOrder;

public class wikiBoard {

    public  static final int keyLength = 64;
    private static final String dateFormat = "yyyyMMddHHmmss";
    private static final int recordSize = 512;

    static SimpleDateFormat SimpleFormatter = new SimpleDateFormat(dateFormat);

    static {
        SimpleFormatter.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    kelondroMap datbase = null;
    kelondroMap bkpbase = null;
    static HashMap<String, String> authors = new HashMap<String, String>();

    public wikiBoard(final File actpath, final File bkppath) {
        new File(actpath.getParent()).mkdirs();
        if (datbase == null) {
            datbase = new kelondroMap(new kelondroBLOBTree(actpath, true, true, keyLength, recordSize, '_', NaturalOrder.naturalOrder, true, false, false), 500);
        }
        new File(bkppath.getParent()).mkdirs();
        if (bkpbase == null) {
            bkpbase = new kelondroMap(new kelondroBLOBTree(bkppath, true, true, keyLength + dateFormat.length(), recordSize, '_', NaturalOrder.naturalOrder, true, false, false), 500);
        }
    }

    public int sizeOfTwo() {
        return datbase.size() + bkpbase.size();
    }

    public int size() {
        return datbase.size();
    }

    public void close() {
        datbase.close();
        bkpbase.close();
    }

    static String dateString() {
        return dateString(new Date());
    }

    public static String dateString(final Date date) {
        synchronized (SimpleFormatter) {
            return SimpleFormatter.format(date);
        }
    }

    private static String normalize(final String key) {
        if (key == null) return "null";
        return key.trim().toLowerCase();
    }

    public static String webalize(String key) {
        if (key == null) return "null";
        key = key.trim().toLowerCase();
        int p;
        while ((p = key.indexOf(" ")) >= 0)
            key = key.substring(0, p) + "%20" + key.substring(p +1);
        return key;
    }

    public static String guessAuthor(final String ip) {
        final String author = authors.get(ip);
        //yacyCore.log.logDebug("DEBUG: guessing author for ip = " + ip + " is '" + author + "', authors = " + authors.toString());
        return author;
    }

    public static void setAuthor(final String ip, final String author) {
        authors.put(ip,author);
    }

    public entry newEntry(final String subject, final String author, final String ip, final String reason, final byte[] page) throws IOException {
        return new entry(normalize(subject), author, ip, reason, page);
    }

    public class entry {

        String key;
        Map<String, String> record;

        public entry(final String subject, String author, String ip, String reason, final byte[] page) throws IOException {
            record = new HashMap<String, String>();
            key = subject;
            if (key.length() > keyLength) key = key.substring(0, keyLength);
            record.put("date", dateString());
            if ((author == null) || (author.length() == 0)) author = "anonymous";
            record.put("author", Base64Order.enhancedCoder.encode(author.getBytes("UTF-8")));
            if ((ip == null) || (ip.length() == 0)) ip = "";
            record.put("ip", ip);
            if ((reason == null) || (reason.length() == 0)) reason = "";
            record.put("reason", Base64Order.enhancedCoder.encode(reason.getBytes("UTF-8")));
            if (page == null)
                record.put("page", "");
            else
                record.put("page", Base64Order.enhancedCoder.encode(page));
            authors.put(ip, author);
            //System.out.println("DEBUG: setting author " + author + " for ip = " + ip + ", authors = " + authors.toString());
        }

        entry(final String key, final Map<String, String> record) {
            this.key = key;
            this.record = record;
        }

        public String subject() {
            return key;
        }

        public Date date() {
            try {
                final String c = record.get("date");
                if (c == null) {
                    System.out.println("DEBUG - ERROR: date field missing in wikiBoard");
                    return new Date();
                }
                synchronized (SimpleFormatter) {
                    return SimpleFormatter.parse(c);
                }
            } catch (final ParseException e) {
                return new Date();
            }
        }

        public String author() {
            final String a = record.get("author");
            if (a == null) return "anonymous";
            final byte[] b = Base64Order.enhancedCoder.decode(a, "de.anomic.data.wikiBoard.author()");
            if (b == null) return "anonymous";
            return new String(b);
        }

        public String reason() {
            final String r = record.get("reason");
            if (r == null) return "";
            final byte[] b = Base64Order.enhancedCoder.decode(r, "de.anomic.data.wikiBoard.reason()");
            if (b == null) return "unknown";
            return new String(b);
        }

        public byte[] page() {
            final String m = record.get("page");
            if (m == null) return new byte[0];
            final byte[] b = Base64Order.enhancedCoder.decode(m, "de.anomic.data.wikiBoard.page()");
            if (b == null) return "".getBytes();
            return b;
        }

        void setAncestorDate(final Date date) {
            record.put("bkp", dateString(date));
        }

        private Date getAncestorDate() {
            try {
                final String c = record.get("date");
                if (c == null) return null;
                synchronized (SimpleFormatter) {
                    return SimpleFormatter.parse(c);
                }
            } catch (final ParseException e) {
                return null;
            }
        }

        /*
	public boolean hasAncestor() {
	    Date ancDate = getAncestorDate();
	    if (ancDate == null) return false;
	    try {
		return bkpbase.has(key + dateString(ancDate));
	    } catch (IOException e) {
		return false;
	    }
	}
         */

        public entry getAncestor() {
            final Date ancDate = getAncestorDate();
            if (ancDate == null) return null;
            return read(key + dateString(ancDate), bkpbase);
        }

        void setChild(final String subject) {
            record.put("child", Base64Order.enhancedCoder.encode(subject.getBytes()));
        }

        private String getChildName() {
            final String c = record.get("child");
            if (c == null) return null;
            final byte[] subject = Base64Order.enhancedCoder.decode(c, "de.anomic.data.wikiBoard.getChildName()");
            if (subject == null) return null;
            return new String(subject);
        }

        public boolean hasChild() {
            final String c = record.get("child");
            if (c == null) return false;
            final byte[] subject = Base64Order.enhancedCoder.decode(c, "de.anomic.data.wikiBoard.hasChild()");
            return (subject != null);
        }

        public entry getChild() {
            final String childName = getChildName();
            if (childName == null) return null;
            return read(childName, datbase);
        }
    }

    public String write(final entry page) {
        // writes a new page and returns key
        try {
            // first load the old page
            final entry oldEntry = read(page.key);
            // set the bkp date of the new page to the date of the old page
            final Date oldDate = oldEntry.date();
            page.setAncestorDate(oldDate);
            oldEntry.setChild(page.subject());
            // write the backup
            //System.out.println("key = " + page.key);
            //System.out.println("oldDate = " + oldDate);
            //System.out.println("record = " + oldEntry.record.toString());
            bkpbase.put(page.key + dateString(oldDate), oldEntry.record);
            // write the new page
            datbase.put(page.key, page.record);
            return page.key;
        } catch (final IOException e) {
            return null;
        }
    }

    public entry read(final String key) {
        return read(key, datbase);
    }

    entry read(String key, final kelondroMap base) {
        try {
            key = normalize(key);
            if (key.length() > keyLength) key = key.substring(0, keyLength);
            final Map<String, String> record = base.get(key);
            if (record == null) return newEntry(key, "anonymous", "127.0.0.1", "New Page", "".getBytes());
            return new entry(key, record);
        } catch (final IOException e) {
            return null;
        }
    }
    
    public entry readBkp(final String key) {
        return read(key, bkpbase);
    }

    /*
    public boolean has(String key) {
	try {
	    return datbase.has(normalize(key));
	} catch (IOException e) {
	    return false;
	}
    }
     */

    public Iterator<byte[]> keys(final boolean up) throws IOException {
        return datbase.keys(up, false);
    }

    public Iterator<byte[]> keysBkp(final boolean up) throws IOException {
        return bkpbase.keys(up, false);
    }
}
