// RSSMessage.java
// (C) 2007 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 16.07.2007 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2006-04-02 22:40:07 +0200 (So, 02 Apr 2006) $
// $LastChangedRevision: 1986 $
// $LastChangedBy: orbiter $
//
// LICENSE
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


package net.yacy.document.content;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;

public class RSSMessage {

    // statics for item generation and automatic categorization
    private static int guidcount = 0;
    private static final String[] tagsDef = new String[] {
        "author",      //
        "copyright",   //
        "category",    //
        "title",       //
        "link",        //
        "referrer",    //
        "language",    //
        "description", //
        "creator",     //
        "pubDate",     //
        "guid",        //
        "docs"         //
    };

    public static final HashSet<String> tags = new HashSet<String>();
    static {
        for (int i = 0; i < tagsDef.length; i++) {
            tags.add(tagsDef[i]);
        }
    }
    
    private final HashMap<String, String> map;

    public RSSMessage(final String title, final String description, final String link) {
        this();
        setValue("title", title);
        setValue("description", description);
        setValue("link", link);
        setValue("pubDate", new Date().toString());
        setValue("guid", Integer.toHexString((title + description + link).hashCode()));
    }
    
    public RSSMessage() {
        this.map = new HashMap<String, String>();
        this.map.put("guid", Long.toHexString(System.currentTimeMillis()) + ":" + guidcount++);
    }
    
    public void setValue(final String name, final String value) {
        map.put(name, value);
    }
    
    public String getAuthor() {
        final String s =  map.get("author");
        return emptyStringOnNull(s);
    }
    
    public String getCopyright() {
        final String s =  map.get("copyright");
        return emptyStringOnNull(s);
    }
    
    public String getCategory() {
        final String s = map.get("category");
        return emptyStringOnNull(s);
    }
    
    public String getTitle() {
        final String s = map.get("title");
        return emptyStringOnNull(s);
    }
    
    public String getLink() {
        final String s =  map.get("link");
        return emptyStringOnNull(s);
    }
    
    public String getReferrer() {
        final String s = map.get("referrer");
        return emptyStringOnNull(s);
    }
    
    public String getLanguage() {
        final String s =  map.get("language");
        return emptyStringOnNull(s);
    }
    
    public String getDescription() {
        final String s =  map.get("description");
        return emptyStringOnNull(s);
    }
    
    public String getCreator() {
        final String s =  map.get("creator");
        return emptyStringOnNull(s);
    }
    
    public String getPubDate() {
        final String s =  map.get("pubDate");
        return emptyStringOnNull(s);
    }
    
    public String getGuid() {
        final String s =  map.get("guid");
        return emptyStringOnNull(s);
    }
    
    public String getDocs() {
        final String s =  map.get("docs");
        return emptyStringOnNull(s);
    }

    /**
     * @param s
     * @return
     */
    private String emptyStringOnNull(final String s) {
        if (s == null) return "";
        return s;
    }
}
