//zipParser.java 
//------------------------
//part of YaCy
//(C) by Michael Peter Christen; mc@yacy.net
//first published on http://www.anomic.de
//Frankfurt, Germany, 2005
//
//this file is contributed by Martin Thelian
//last major change: 16.05.2005
//
//This program is free software; you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation; either version 2 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program; if not, write to the Free Software
//Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package de.anomic.plasma.parser.zip;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import de.anomic.htmlFilter.htmlFilterContentScraper;
import de.anomic.htmlFilter.htmlFilterImageEntry;
import de.anomic.kelondro.util.ByteBuffer;
import de.anomic.kelondro.util.FileUtils;
import de.anomic.plasma.plasmaParser;
import de.anomic.plasma.plasmaParserDocument;
import de.anomic.plasma.parser.AbstractParser;
import de.anomic.plasma.parser.Parser;
import de.anomic.plasma.parser.ParserException;
import de.anomic.yacy.yacyURL;

public class zipParser extends AbstractParser implements Parser {

    /**
     * a list of mime types that are supported by this parser class
     * @see #getSupportedMimeTypes()
     */
    public static final Hashtable<String, String> SUPPORTED_MIME_TYPES = new Hashtable<String, String>(); 
    static { 
        SUPPORTED_MIME_TYPES.put("application/zip","zip");
        SUPPORTED_MIME_TYPES.put("application/x-zip","zip");
        SUPPORTED_MIME_TYPES.put("application/x-zip-compressed","zip");
        SUPPORTED_MIME_TYPES.put("application/java-archive","jar");
    }     

    /**
     * a list of library names that are needed by this parser
     * @see Parser#getLibxDependences()
     */
    private static final String[] LIBX_DEPENDENCIES = new String[] {};        
    
    public zipParser() {        
        super(LIBX_DEPENDENCIES);
        this.parserName = "Compressed Archive File Parser"; 
    }
    
    public Hashtable<String, String> getSupportedMimeTypes() {
        return SUPPORTED_MIME_TYPES;
    }
    
    public plasmaParserDocument parse(final yacyURL location, final String mimeType, final String charset, final InputStream source) throws ParserException, InterruptedException {
        
        long docTextLength = 0;
        OutputStream docText = null;
        File outputFile = null;
        plasmaParserDocument subDoc = null;
        try {           
            if ((this.contentLength == -1) || (this.contentLength > Parser.MAX_KEEP_IN_MEMORY_SIZE)) {
                outputFile = File.createTempFile("zipParser",".prt");
                docText = new BufferedOutputStream(new FileOutputStream(outputFile));
            } else {
                docText = new ByteBuffer();
            }
            
            final StringBuilder docKeywords = new StringBuilder();
            final StringBuilder docLongTitle = new StringBuilder();   
            final LinkedList<String> docSections = new LinkedList<String>();
            final StringBuilder docAbstrct = new StringBuilder();
            final Map<yacyURL, String> docAnchors = new HashMap<yacyURL, String>();
            final HashMap<String, htmlFilterImageEntry> docImages = new HashMap<String, htmlFilterImageEntry>(); 
            
            // creating a new parser class to parse the unzipped content
            final plasmaParser theParser = new plasmaParser();            
            
            // looping through the contained files
            ZipEntry entry;
            final ZipInputStream zippedContent = new ZipInputStream(source);                      
            while ((entry = zippedContent.getNextEntry()) !=null) {
                // check for interruption
                checkInterruption();                
                
                // skip directories
                if (entry.isDirectory()) continue;
                
                // Get the entry name
                final String entryName = entry.getName();                
                final int idx = entryName.lastIndexOf(".");
                
                // getting the file extension
                final String entryExt = (idx > -1) ? entryName.substring(idx+1) : "";
                
                // trying to determine the mimeType per file extension   
                final String entryMime = plasmaParser.getMimeTypeByFileExt(entryExt);      
                
                // parsing the content
                File subDocTempFile = null;
                try {
                    // create the temp file
                    subDocTempFile = createTempFile(entryName);
                    
                    // copy the data into the file
                    FileUtils.copy(zippedContent,subDocTempFile,entry.getSize());                    
                    
                    // parsing the zip file entry
                    subDoc = theParser.parseSource(yacyURL.newURL(location,"#" + entryName),entryMime,null, subDocTempFile);
                } catch (final ParserException e) {
                    this.theLogger.logInfo("Unable to parse zip file entry '" + entryName + "'. " + e.getMessage());
                } finally {
                    if (subDocTempFile != null) FileUtils.deletedelete(subDocTempFile);
                }
                if (subDoc == null) continue;
                
                // merging all documents together
                if (docKeywords.length() > 0) docKeywords.append(",");
                docKeywords.append(subDoc.dc_subject(','));
                
                if (docLongTitle.length() > 0) docLongTitle.append("\n");
                docLongTitle.append(subDoc.dc_title());
                
                docSections.addAll(Arrays.asList(subDoc.getSectionTitles()));
                
                if (docAbstrct.length() > 0) docAbstrct.append("\n");
                docAbstrct.append(subDoc.dc_description());

                if (subDoc.getTextLength() > 0) {
                    if (docTextLength > 0) docText.write('\n');
                    docTextLength += FileUtils.copy(subDoc.getText(), docText);
                }
                
                //docAnchors.putAll(subDoc.getAnchors());
                htmlFilterContentScraper.addAllImages(docImages, subDoc.getImages());
                
                // release subdocument
                subDoc.close();
                subDoc = null;
            }
            
        
            plasmaParserDocument result = null;
            
            if (docText instanceof ByteBuffer) {
                result = new plasmaParserDocument(
                    location,
                    mimeType,
                    null,
                    null,
                    docKeywords.toString().split(" |,"),
                    docLongTitle.toString(),
                    "", // TODO: AUTHOR
                    docSections.toArray(new String[docSections.size()]),
                    docAbstrct.toString(),
                    ((ByteBuffer)docText).getBytes(),
                    docAnchors,
                    docImages);
            } else {
                result = new plasmaParserDocument(
                        location,
                        mimeType,
                        null,
                        null,
                        docKeywords.toString().split(" |,"),
                        docLongTitle.toString(),
                        "", // TODO: AUTHOR
                        docSections.toArray(new String[docSections.size()]),
                        docAbstrct.toString(),
                        outputFile,
                        docAnchors,
                        docImages);                
            }
            
            return result;
        } catch (final Exception e) {  
            if (e instanceof InterruptedException) throw (InterruptedException) e;
            if (e instanceof ParserException) throw (ParserException) e;
            
            if (subDoc != null) subDoc.close();
            
            // close the writer
            if (docText != null) try { docText.close(); } catch (final Exception ex) {/* ignore this */}
            
            // delete the file
            if (outputFile != null) FileUtils.deletedelete(outputFile);
            
            throw new ParserException("Unexpected error while parsing zip resource. " + e.getClass().getName() + ": "+ e.getMessage(),location);
        }
    }
    
    public void reset() {
        // Nothing todo here at the moment
        super.reset();
    }
}
