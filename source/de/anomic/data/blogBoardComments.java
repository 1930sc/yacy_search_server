// wikiBoard.java 
// -------------------------------------
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004
// last major change: 20.07.2004
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

// This file is contributed by Jan Sandbrink
// based on the Code of wikiBoard.java

package de.anomic.data;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import net.yacy.kelondro.blob.Heap;
import net.yacy.kelondro.blob.MapView;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.order.Base64Order;
import net.yacy.kelondro.order.NaturalOrder;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import de.anomic.data.wiki.wikiBoard;

public class blogBoardComments {
    
    public  static final int keyLength = 64;
    private static final String dateFormat = "yyyyMMddHHmmss";

    static SimpleDateFormat SimpleFormatter = new SimpleDateFormat(dateFormat, Locale.US);
    static {
        SimpleFormatter.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    
    private MapView database = null;
    
    public blogBoardComments(final File actpath) throws IOException {
        new File(actpath.getParent()).mkdir();
        if (database == null) {
            //database = new MapView(BLOBTree.toHeap(actpath, true, true, keyLength, recordSize, '_', NaturalOrder.naturalOrder, newFile), 500, '_');
            database = new MapView(new Heap(actpath, keyLength, NaturalOrder.naturalOrder, 1024 * 64), 500, '_');
        }
    }
    
    public int size() {
        return database.size();
    }
    
    public void close() {
        database.close();
    }
    
    static String dateString(final Date date) {
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
    public String guessAuthor(final String ip) {
        return wikiBoard.guessAuthor(ip);
    }
    public CommentEntry newEntry(final String key, final byte[] subject, final byte[] author, final String ip, final Date date, final byte[] page) {
        return new CommentEntry(normalize(key), subject, author, ip, date, page);
    }
    public String write(final CommentEntry page) {
        // writes a new page and returns key
    	try {
    	    database.put(page.key, page.record);
    	    return page.key;
    	} catch (final Exception e) {
    	    Log.logException(e);
    	    return null;
    	}
    }
    public CommentEntry read(final String key) {
        //System.out.println("DEBUG: read from blogBoardComments");
        return read(key, database);
    }
    private CommentEntry read(String key, final MapView base) {
        key = normalize(key);
        if (key.length() > keyLength) key = key.substring(0, keyLength);
        Map<String, String> record;
        try {
            record = base.get(key);
        } catch (final IOException e) {
            record = null;
        }
        if (record == null) return newEntry(key, "".getBytes(), "anonymous".getBytes(), "127.0.0.1", new Date(), "".getBytes());
        return new CommentEntry(key, record);
    }
    public boolean importXML(final String input) {
    	final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    	try {
			final DocumentBuilder builder = factory.newDocumentBuilder();
			final Document doc = builder.parse(new ByteArrayInputStream(input.getBytes()));
			return parseXMLimport(doc);
		} catch (final ParserConfigurationException e) {
		} catch (final SAXException e) {
		} catch (final IOException e) {}
		
    	return false;
    }
    private boolean parseXMLimport(final Document doc) {
    	if(!doc.getDocumentElement().getTagName().equals("blog"))
    		return false;
    	
    	final NodeList items = doc.getDocumentElement().getElementsByTagName("item");
    	if(items.getLength() == 0)
    		return false; 
    	
    	for(int i=0;i<items.getLength();++i) {
    		String key = null, ip = null, StrSubject = null, StrAuthor = null, StrPage = null, StrDate = null;
    		Date date = null;
    		
    		if(!items.item(i).getNodeName().equals("item"))
    			continue;
    		
    		final NodeList currentNodeChildren = items.item(i).getChildNodes();
    		
    		for(int j=0;j<currentNodeChildren.getLength();++j) {
    			final Node currentNode = currentNodeChildren.item(j);
    			if(currentNode.getNodeName().equals("id"))
    				key = currentNode.getFirstChild().getNodeValue();
    			else if(currentNode.getNodeName().equals("ip"))
    				ip = currentNode.getFirstChild().getNodeValue();
    			else if(currentNode.getNodeName().equals("timestamp"))
    				StrDate = currentNode.getFirstChild().getNodeValue();
    			else if(currentNode.getNodeName().equals("subject"))
    				StrSubject = currentNode.getFirstChild().getNodeValue();
    			else if(currentNode.getNodeName().equals("author"))
    				StrAuthor = currentNode.getFirstChild().getNodeValue();
    			else if(currentNode.getNodeName().equals("content"))
    				StrPage = currentNode.getFirstChild().getNodeValue();
    		}
    		
    		try {
				date = SimpleFormatter.parse(StrDate);
			} catch (final ParseException e1) {
				date = new Date();
			}
    		
    		if(key == null || ip == null || StrSubject == null || StrAuthor == null || StrPage == null || date == null)
    			return false;
    		
    		byte[] subject,author,page;
    		try {
				subject = StrSubject.getBytes("UTF-8");
			} catch (final UnsupportedEncodingException e1) {
				subject = StrSubject.getBytes();
			}
			try {
				author = StrAuthor.getBytes("UTF-8");
			} catch (final UnsupportedEncodingException e1) {
				author = StrAuthor.getBytes();
			}
			try {
				page = StrPage.getBytes("UTF-8");
			} catch (final UnsupportedEncodingException e1) {
				page = StrPage.getBytes();
			}

		write (newEntry(key, subject, author, ip, date, page));
    	}
    	return true;
    }
    public void delete(String key) {
    	key = normalize(key);
    	try {
			database.remove(key);
		} catch (final IOException e) { }
    }
    public Iterator<byte[]> keys(final boolean up) throws IOException {
        return database.keys(up, false);
    }

    public static class CommentEntry {
        
        String key;
        Map<String, String> record;
    
        public CommentEntry(final String nkey, final byte[] subject, final byte[] author, final String ip, final Date date, final byte[] page) {
            record = new HashMap<String, String>();
            
            setKey(nkey);
            setDate(date);
            setSubject(subject);
            setAuthor(author);
            setIp(ip);
            setPage(page);
            
            wikiBoard.setAuthor(ip, new String(author));
        }
    
        CommentEntry(final String key, final Map<String, String> record) {
            this.key = key;
            this.record = record;
            if (this.record.get("comments")==null) this.record.put("comments", listManager.collection2string(new ArrayList<String>()));
        }
        
        public String getKey() {
            return key;
        }
        private void setKey(final String var) {
            key = var;
            if (key.length() > keyLength) 
                key = var.substring(0, keyLength);
        }
        private void setSubject(final byte[] subject) {
            if (subject == null) 
                record.put("subject","");
            else 
                record.put("subject", Base64Order.enhancedCoder.encode(subject));
        }
        public byte[] getSubject() {
            final String subject = record.get("subject");
            if (subject == null) return new byte[0];
            final byte[] subject_bytes = Base64Order.enhancedCoder.decode(subject);
            if (subject_bytes == null) return "".getBytes();
            return subject_bytes;
        }
        private void setDate(Date date) {
            if(date == null) 
                date = new Date(); 
            record.put("date", dateString(date));
        }
        public Date getDate() {
            try {
                final String date = record.get("date");
                if (date == null) {
                    if (Log.isFinest("Blog")) Log.logFinest("Blog", "ERROR: date field missing in blogBoard");
                    return new Date();
                }
                synchronized (SimpleFormatter) {
                    return SimpleFormatter.parse(date);
                }
            } catch (final ParseException e) {
                return new Date();
            }
        }
        
        public String getTimestamp() {
            final String timestamp = record.get("date");
            if (timestamp == null) {
                if (Log.isFinest("Blog")) Log.logFinest("Blog", "ERROR: date field missing in blogBoard");
                return dateString(new Date());
            }
            return timestamp;
        }
        private void setAuthor(final byte[] author) {
            if (author == null) 
                record.put("author","");
            else 
                record.put("author", Base64Order.enhancedCoder.encode(author));
        }
        public byte[] getAuthor() {
            final String author = record.get("author");
            if (author == null) 
                return new byte[0];
            final byte[] author_byte = Base64Order.enhancedCoder.decode(author);
            if (author_byte == null) 
                return "".getBytes();
            return author_byte;
        }
        private void setIp(String ip) {
            if ((ip == null) || (ip.length() == 0)) 
                ip = "";
            record.put("ip", ip);
        }
        public String getIp() {
            final String ip = record.get("ip");
            if (ip == null) 
                return "127.0.0.1";
            return ip;
        }
        private void setPage(final byte[] page) {
            if (page == null) 
                record.put("page", "");
            else 
                record.put("page", Base64Order.enhancedCoder.encode(page));
        }
        public byte[] getPage() {
            final String page = record.get("page");
            if (page == null) 
                return new byte[0];
            final byte[] page_byte = Base64Order.enhancedCoder.decode(page);
            if (page_byte == null) 
                return "".getBytes();
            return page_byte;
        }        
        /**
         * Is the comment allowed? 
         * this is possible for moderated blog entry only and means 
         * the administrator has explicit allowed the comment.
         * @return 
         */
        public boolean isAllowed() {
            return (record.get("moderated") != null) && record.get("moderated").equals("true");
        } 
        public void allow() {
            record.put("moderated", "true");
        } 
    
    }

}
