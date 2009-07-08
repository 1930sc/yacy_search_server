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

package de.anomic.document.parser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.catcode.odf.ODFMetaFileAnalyzer;
import com.catcode.odf.OpenDocumentMetadata;
import com.catcode.odf.OpenDocumentTextInputStream;

import de.anomic.crawler.HTTPLoader;
import de.anomic.document.AbstractParser;
import de.anomic.document.Parser;
import de.anomic.document.ParserException;
import de.anomic.document.Document;
import de.anomic.http.httpClient;
import de.anomic.http.httpHeader;
import de.anomic.http.httpRequestHeader;
import de.anomic.kelondro.util.FileUtils;
import de.anomic.server.serverCharBuffer;
import de.anomic.yacy.yacyURL;

public class odtParser extends AbstractParser implements Parser {

    /**
     * a list of mime types that are supported by this parser class
     * @see #getSupportedMimeTypes()
     */
    public static final Hashtable<String, String> SUPPORTED_MIME_TYPES = new Hashtable<String, String>();
    static { 
        SUPPORTED_MIME_TYPES.put("application/vnd.oasis.opendocument.text","odt");
        SUPPORTED_MIME_TYPES.put("application/x-vnd.oasis.opendocument.text","odt");
    }     

    /**
     * a list of library names that are needed by this parser
     * @see Parser#getLibxDependences()
     */
    private static final String[] LIBX_DEPENDENCIES = new String[] {"odf_utils_05_11_29.jar"};        
    
    public odtParser() {        
        super(LIBX_DEPENDENCIES);
        this.parserName = "OASIS OpenDocument V2 Text Document Parser"; 
    }
    
    public Hashtable<String, String> getSupportedMimeTypes() {
        return SUPPORTED_MIME_TYPES;
    }
    
    public Document parse(final yacyURL location, final String mimeType, final String charset, final File dest) throws ParserException, InterruptedException {
        
        Writer writer = null;
        File writerFile = null;
        try {          
            String docDescription = null;
            String docKeywordStr  = null;
            String docShortTitle  = null;
            String docLongTitle   = null;
            String docAuthor      = null;
            String docLanguage    = null;
            
            // opening the file as zip file
            final ZipFile zipFile= new ZipFile(dest);
            final Enumeration<? extends ZipEntry> zipEnum = zipFile.entries();
            
            // looping through all containing files
            while (zipEnum.hasMoreElements()) {
                // check for interruption
                checkInterruption();
                
                // getting the next zip file entry
                final ZipEntry zipEntry= zipEnum.nextElement();
                final String entryName = zipEntry.getName();
                
                // content.xml contains the document content in xml format
                if (entryName.equals("content.xml")) {
                    final long contentSize = zipEntry.getSize();
                    
                    // creating a writer for output
                    if ((contentSize == -1) || (contentSize > Parser.MAX_KEEP_IN_MEMORY_SIZE)) {
                        writerFile = File.createTempFile("odtParser",".prt");
                        writer = new OutputStreamWriter(new FileOutputStream(writerFile),"UTF-8");
                    } else {
                        writer = new serverCharBuffer(); 
                    }                    
                    
                    // extract data
                    final InputStream zipFileEntryStream = zipFile.getInputStream(zipEntry);
                    final OpenDocumentTextInputStream odStream = new OpenDocumentTextInputStream(zipFileEntryStream);
                    FileUtils.copy(odStream, writer, Charset.forName("UTF-8"));
                
                    // close readers and writers
                    odStream.close();
                    writer.close();
                    
                } else if (entryName.equals("meta.xml")) {
                    //  meta.xml contains metadata about the document
                    final InputStream zipFileEntryStream = zipFile.getInputStream(zipEntry);
                    final ODFMetaFileAnalyzer metaAnalyzer = new ODFMetaFileAnalyzer();
                    final OpenDocumentMetadata metaData = metaAnalyzer.analyzeMetaData(zipFileEntryStream);
                    docDescription = metaData.getDescription();
                    docKeywordStr  = metaData.getKeyword();
                    docShortTitle  = metaData.getTitle();
                    docLongTitle   = metaData.getSubject();
                    docAuthor      = metaData.getCreator();
                    docLanguage    = metaData.getLanguage();
                }
            }
            
            // make the languages set
            Set<String> languages = new HashSet<String>(1);
            if (docLanguage != null) languages.add(docLanguage);
            
            // if there is no title availabe we generate one
            if (docLongTitle == null) {
                if (docShortTitle != null) {
                    docLongTitle = docShortTitle;
                } 
            }            
         
            // split the keywords
            String[] docKeywords = null;
            if (docKeywordStr != null) docKeywords = docKeywordStr.split(" |,");
            
            // create the parser document
            Document theDoc = null;
            if (writer instanceof serverCharBuffer) {
                final byte[] contentBytes = ((serverCharBuffer)writer).toString().getBytes("UTF-8");
                theDoc = new Document(
                        location,
                        mimeType,
                        "UTF-8",
                        languages,
                        docKeywords,
                        docLongTitle,
                        docAuthor,
                        null,
                        docDescription,
                        contentBytes,
                        null,
                        null);
            } else {
                theDoc = new Document(
                        location,
                        mimeType,
                        "UTF-8",
                        languages,
                        docKeywords,
                        docLongTitle,
                        docAuthor,
                        null,
                        docDescription,
                        writerFile,
                        null,
                        null);
            }
            return theDoc;
        } catch (final Exception e) {            
            if (e instanceof InterruptedException) throw (InterruptedException) e;
            if (e instanceof ParserException) throw (ParserException) e;
            
            // close the writer
            if (writer != null) try { writer.close(); } catch (final Exception ex) {/* ignore this */}
            
            // delete the file
            if (writerFile != null) FileUtils.deletedelete(writerFile);
            
            throw new ParserException("Unexpected error while parsing odt file. " + e.getMessage(),location); 
        }
    }
    
    public Document parse(final yacyURL location, final String mimeType, final String charset, final InputStream source) throws ParserException, InterruptedException {
        File dest = null;
        try {
            // creating a tempfile
            dest = File.createTempFile("OpenDocument", ".odt");
            dest.deleteOnExit();
            
            // copying the stream into a file
            FileUtils.copy(source, dest);
            
            // parsing the content
            return parse(location, mimeType, charset, dest);
        } catch (final Exception e) {
            if (e instanceof InterruptedException) throw (InterruptedException) e;
            if (e instanceof ParserException) throw (ParserException) e;
            
            throw new ParserException("Unexpected error while parsing odt file. " + e.getMessage(),location); 
        } finally {
            if (dest != null) FileUtils.deletedelete(dest);
        }
    }
    
    public void reset() {
        // Nothing todo here at the moment
        super.reset();
    }
    
    public static void main(final String[] args) {
        try {
            if (args.length != 1) return;
            
            // getting the content URL
            final yacyURL contentUrl = new yacyURL(args[0], null);
            
            // creating a new parser
            final odtParser testParser = new odtParser();
            
            // downloading the document content
            final httpRequestHeader reqHeader = new httpRequestHeader();
            reqHeader.put(httpHeader.USER_AGENT, HTTPLoader.crawlerUserAgent);
            final byte[] content = httpClient.wget(contentUrl.toString(), reqHeader, 10000);
            final ByteArrayInputStream input = new ByteArrayInputStream(content);
            
            // parsing the document
            testParser.parse(contentUrl, "application/vnd.oasis.opendocument.text", null, input);            
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }
}
