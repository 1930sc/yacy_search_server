//xlsParser.java 
//------------------------
//part of YaCy
//(C) by Michael Peter Christen; mc@yacy.net
//first published on http://www.anomic.de
//Frankfurt, Germany, 2005
//
//this file is contributed by Tim Riemann
//
// $LastChangedDate:$
// $LastChangedRevision:$
// $LastChangedBy:$
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

package de.anomic.plasma.parser.xls;

import java.io.InputStream;
import java.util.Hashtable;

import org.apache.poi.hssf.eventusermodel.HSSFEventFactory;
import org.apache.poi.hssf.eventusermodel.HSSFListener;
import org.apache.poi.hssf.eventusermodel.HSSFRequest;
import org.apache.poi.hssf.record.LabelSSTRecord;
import org.apache.poi.hssf.record.NumberRecord;
import org.apache.poi.hssf.record.Record;
import org.apache.poi.hssf.record.SSTRecord;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import de.anomic.plasma.plasmaParserDocument;
import de.anomic.plasma.parser.AbstractParser;
import de.anomic.plasma.parser.Parser;
import de.anomic.plasma.parser.ParserException;
import de.anomic.yacy.yacyURL;

public class xlsParser extends AbstractParser implements Parser, HSSFListener {

    //StringBuilder for parsed text
    private StringBuilder sbFoundStrings = null;
    
    //sstrecord needed for event parsing
    private SSTRecord sstrec;
    
    /**
     * a list of mime types that are supported by this parser class
     * @see #getSupportedMimeTypes()
     */
    public static final Hashtable<String, String> SUPPORTED_MIME_TYPES = new Hashtable<String, String>();
    static { 
        SUPPORTED_MIME_TYPES.put("application/msexcel","xls");
        SUPPORTED_MIME_TYPES.put("application/excel","xls");
        SUPPORTED_MIME_TYPES.put("application/vnd.ms-excel","xls");
        SUPPORTED_MIME_TYPES.put("application/x-excel","xls");
        SUPPORTED_MIME_TYPES.put("application/x-msexcel","xls");
    }     

    /**
     * a list of library names that are needed by this parser
     * @see Parser#getLibxDependences()
     */
    private static final String[] LIBX_DEPENDENCIES = new String[] {
        "poi-3.2-FINAL-20081019.jar",
        "poi-scratchpad-3.2-FINAL-20081019.jar"
    }; 

    public xlsParser(){
        super(LIBX_DEPENDENCIES);
        this.parserName = "Microsoft Excel Parser";
        this.parserVersionNr = "0.1"; 
    }

    /*
     * parses the source documents and returns a plasmaParserDocument containing
     * all extracted information about the parsed document
     */ 
    public plasmaParserDocument parse(final yacyURL location, final String mimeType,
            final String charset, final InputStream source) throws ParserException,
            InterruptedException {
        try {
            //generate new StringBuilder for parsing
            sbFoundStrings = new StringBuilder();
            
            //create a new org.apache.poi.poifs.filesystem.Filesystem
            final POIFSFileSystem poifs = new POIFSFileSystem(source);
            //get the Workbook (excel part) stream in a InputStream
            final InputStream din = poifs.createDocumentInputStream("Workbook");
            //construct out HSSFRequest object
            final HSSFRequest req = new HSSFRequest();
            //lazy listen for ALL records with the listener shown above
            req.addListenerForAllRecords(this);
            //create our event factory
            final HSSFEventFactory factory = new HSSFEventFactory();
            //process our events based on the document input stream
            factory.processEvents(req, din);
            //close our document input stream (don't want to leak these!)
            din.close();
            
            //now the parsed strings are in the StringBuilder, now convert them to a String
            final String contents = sbFoundStrings.toString().trim();
            
            /*
             * create the plasmaParserDocument for the database
             * and set shortText and bodyText properly
             */
            final plasmaParserDocument theDoc = new plasmaParserDocument(
                    location,
                    mimeType,
                    "UTF-8",
                    null,
                    null,
                    location.getFile(),
                    "", // TODO: AUTHOR
                    null,
                    null,
                    contents.getBytes("UTF-8"),
                    null,
                    null);
            return theDoc;
        } catch (final Exception e) { 
            if (e instanceof InterruptedException) throw (InterruptedException) e;

            /*
             * an unexpected error occurred, log it and throw a ParserException
             */            
            final String errorMsg = "Unable to parse the xls document '" + location + "':" + e.getMessage();
            this.theLogger.logSevere(errorMsg);            
            throw new ParserException(errorMsg, location);
        } finally {
            sbFoundStrings = null;
        }
    }
    
    public Hashtable<String, String> getSupportedMimeTypes() {
        return SUPPORTED_MIME_TYPES;
    }

    @Override
    public void reset(){
        //nothing to do
        super.reset();
    }

    public void processRecord(final Record record) {
        switch (record.getSid()){
            case NumberRecord.sid: {
                final NumberRecord numrec = (NumberRecord) record;
                sbFoundStrings.append(numrec.getValue());
                break;
            }
            //unique string records
            case SSTRecord.sid: {
                sstrec = (SSTRecord)record;
                for (int k = 0; k < sstrec.getNumUniqueStrings(); k++){
                    sbFoundStrings.append( sstrec.getString(k) );
                    
                    //add line seperator
                    sbFoundStrings.append( "\n" );
                }
                break;
            }
            
            case LabelSSTRecord.sid: {
                final LabelSSTRecord lsrec = (LabelSSTRecord)record;
                sbFoundStrings.append( sstrec.getString(lsrec.getSSTIndex()) );
                break;
            }
        }
        
        //add line seperator
        sbFoundStrings.append( "\n" );
    }
}
