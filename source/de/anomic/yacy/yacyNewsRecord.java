// yacyNewsRecord.java
// -----------------------
// part of YaCy
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2005
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
// Using this software in any meaning (reading, learning, copying, compiling,
// running) means that you agree that the Author(s) is (are) not responsible
// for cost, loss of data or any harm that may be caused directly or indirectly
// by usage of this softare or this documentation. The usage of this software
// is on your own risk. The installation and usage (starting/running) of this
// software may allow other people or application to access your computer and
// any attached devices and is highly dependent on the configuration of the
// software which must be done by the user of the software; the author(s) is
// (are) also not responsible for proper configuration and usage of the
// software, even if provoked by documentation provided together with
// the software.
//
// Any changes to this file according to the GPL as documented in the file
// gpl.txt aside this file in the shipment you received can be done to the
// lines that follows this copyright notice here, but changes must not be
// done inside the copyright notice above. A re-distribution must contain
// the intact and unchanged copyright notice.
// Contributions and changes to the program code must be marked as such.

package de.anomic.yacy;

import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Map.Entry;

import de.anomic.kelondro.coding.DateFormatter;
import de.anomic.kelondro.coding.NaturalOrder;
import de.anomic.kelondro.index.Row;
import de.anomic.server.serverCodings;

public class yacyNewsRecord {

    public static final int maxNewsRecordLength  = 512;
    public static final int categoryStringLength = 8;
    public static final int idLength = DateFormatter.PATTERN_SHORT_SECOND.length() + yacySeedDB.commonHashLength;

    private final String originator;  // hash of originating peer
    private final Date   created;     // Date when news was created by originator
    private final Date   received;    // Date when news was received here at this peer
    private final String category;    // keyword that adresses possible actions
    private int    distributed; // counter that counts number of distributions of this news record
    private final Map<String, String> attributes;  // elemets of the news for a special category

    public static final int attributesMaxLength = maxNewsRecordLength
                                                  - idLength
                                                  - categoryStringLength
                                                  - DateFormatter.PATTERN_SHORT_SECOND.length()
                                                  - 2;
    
    public static final Row rowdef = new Row(
            "String idx-" + idLength + " \"id = created + originator\"," +
            "String cat-" + categoryStringLength + "," +
            "String rec-" + DateFormatter.PATTERN_SHORT_SECOND.length() + "," +
            "short  dis-2 {b64e}," +
            "String att-" + attributesMaxLength,
            NaturalOrder.naturalOrder, 0
    );
    
    public static yacyNewsRecord newRecord(final String newsString) {
        try {
            return new yacyNewsRecord(newsString);
        } catch (final IllegalArgumentException e) {
            yacyCore.log.logWarning("rejected bad yacy news record: " + e.getMessage());
            return null;
        }
    }

    public static yacyNewsRecord newRecord(final yacySeed mySeed, final String category, final Properties attributes) {
        try {
            final HashMap<String, String> m = new HashMap<String, String>();
            final Iterator<Entry<Object, Object>> e = attributes.entrySet().iterator();
            Map.Entry<Object, Object> entry;
            while (e.hasNext()) {
                entry = e.next();
                m.put((String) entry.getKey(), (String) entry.getValue());
            }
            return new yacyNewsRecord(mySeed, category, m);
        } catch (final IllegalArgumentException e) {
            yacyCore.log.logWarning("rejected bad yacy news record: " + e.getMessage());
            return null;
        }
    }
    
    public static yacyNewsRecord newRecord(final yacySeed mySeed, final String category, final Map<String, String> attributes) {
        try {
            return new yacyNewsRecord(mySeed, category, attributes);
        } catch (final IllegalArgumentException e) {
            yacyCore.log.logWarning("rejected bad yacy news record: " + e.getMessage());
            return null;
        }
    }
    
    public static yacyNewsRecord newRecord(final String id, final String category, final Date received, final int distributed, final Map<String, String> attributes) {
        try {
            return new yacyNewsRecord(id, category, received, distributed, attributes);
        } catch (final IllegalArgumentException e) {
            yacyCore.log.logWarning("rejected bad yacy news record: " + e.getMessage());
            return null;
        }
    }
    
    public yacyNewsRecord(final String newsString) {
        this.attributes = serverCodings.string2map(newsString, ",");
        if (attributes.toString().length() > attributesMaxLength) throw new IllegalArgumentException("attributes length (" + attributes.toString().length() + ") exceeds maximum (" + attributesMaxLength + ")");
        this.category = (attributes.containsKey("cat")) ? (String) attributes.get("cat") : "";
        if (category.length() > categoryStringLength) throw new IllegalArgumentException("category length (" + category.length() + ") exceeds maximum (" + categoryStringLength + ")");
        this.received = (attributes.containsKey("rec")) ? DateFormatter.parseShortSecond(attributes.get("rec"), DateFormatter.UTCDiffString()) : new Date();
        this.created = (attributes.containsKey("cre")) ? DateFormatter.parseShortSecond(attributes.get("cre"), DateFormatter.UTCDiffString()) : new Date();
        this.distributed = (attributes.containsKey("dis")) ? Integer.parseInt(attributes.get("dis")) : 0;
        this.originator = (attributes.containsKey("ori")) ? (String) attributes.get("ori") : "";
        removeStandards();
    }

    public yacyNewsRecord(final yacySeed mySeed, final String category, final Map<String, String> attributes) {
        if (category.length() > categoryStringLength) throw new IllegalArgumentException("category length (" + category.length() + ") exceeds maximum (" + categoryStringLength + ")");
        if (attributes.toString().length() > attributesMaxLength) throw new IllegalArgumentException("attributes length (" + attributes.toString().length() + ") exceeds maximum (" + attributesMaxLength + ")");
        this.attributes = attributes;
        this.received = null;
        this.created = new Date();
        this.category = category;
        this.distributed = 0;
        this.originator = mySeed.hash;
        removeStandards();
    }

    protected yacyNewsRecord(final String id, final String category, final Date received, final int distributed, final Map<String, String> attributes) {
        if (category.length() > categoryStringLength) throw new IllegalArgumentException("category length (" + category.length() + ") exceeds maximum (" + categoryStringLength + ")");
        if (attributes.toString().length() > attributesMaxLength) throw new IllegalArgumentException("attributes length (" + attributes.toString().length() + ") exceeds maximum (" + attributesMaxLength + ")");
        this.attributes = attributes;
        this.received = received;
        this.created = DateFormatter.parseShortSecond(id.substring(0, DateFormatter.PATTERN_SHORT_SECOND.length()), DateFormatter.UTCDiffString());
        this.category = category;
        this.distributed = distributed;
        this.originator = id.substring(DateFormatter.PATTERN_SHORT_SECOND.length());
        removeStandards();
    }

    private void removeStandards() {
        attributes.remove("ori");
        attributes.remove("cat");
        attributes.remove("cre");
        attributes.remove("rec");
        attributes.remove("dis");
    }
    
    public String toString() {
        // this creates the string that shall be distributed
        // attention: this has no additional encoding
        if (this.originator != null) attributes.put("ori", this.originator);
        if (this.category != null)   attributes.put("cat", this.category);
        if (this.created != null)    attributes.put("cre", DateFormatter.formatShortSecond(this.created));
        if (this.received != null)   attributes.put("rec", DateFormatter.formatShortSecond(this.received));
        attributes.put("dis", Integer.toString(this.distributed));
        final String theString = attributes.toString();
        removeStandards();
        return theString;
    }

    public String id() {
        return DateFormatter.formatShortSecond(created) + originator;
    }

    public String originator() {
        return originator;
    }

    public Date created() {
        return created;
    }

    public Date received() {
        return received;
    }

    public String category() {
        return category;
    }

    public int distributed() {
        return distributed;
    }

    public void incDistribution() {
        distributed++;
    }

    public Map<String, String> attributes() {
        return attributes;
    }
    
    public String attribute(final String key, final String dflt) {
        final String s = attributes.get(key);
        if ((s == null) || (s.length() == 0)) return dflt;
        return s;
    }

    public static void main(final String[] args) {
        System.out.println((newRecord(args[0])).toString());
    }
}