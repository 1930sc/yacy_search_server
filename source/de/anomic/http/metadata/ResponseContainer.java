// ResponseContainer.java
// (C) 2008 by Daniel Raap; danielr@users.berlios.de
// first published 2.4.2008 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
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
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA

package de.anomic.http.metadata;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import net.yacy.kelondro.util.FileUtils;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpMethod;

import de.anomic.http.client.ConnectionInfo;
import de.anomic.http.io.ByteCountInputStream;

/**
 * container for http-response data
 * 
 * @author daniel
 * @since 21.03.2008
 */
public class ResponseContainer {
	private final HttpMethod method;
	private String incomingAccountingName = null;

	/**
	 * cache of body-data
	 */
	private byte[] responseBody;

	/**
	 * constructor
	 * 
	 * @param method
	 * @throws IOException
	 */
	public ResponseContainer(final HttpMethod method) {
		super();

		this.method = method;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.anomic.http.HttpResponse#getResponseHeader()
	 */
	public ResponseHeader getResponseHeader() {
		final ResponseHeader responseHeader = new ResponseHeader();
		for (final Header header : method.getResponseHeaders()) {
			responseHeader.add(header.getName(), header.getValue());
		}
		return responseHeader;
	}

	/**
	 * @see org.apache.commons.httpclient.HttpMethod#getResponseBody()
	 * @return
	 * @throws IOException
	 */
	public byte[] getData() throws IOException {
		if (responseBody == null) {
			InputStream instream = null;
			try {
				instream = getDataAsStream();
				if (instream != null) {
					responseBody = FileUtils.read(instream);
				}
			} finally {
				if (instream != null) {
					closeStream();
				}
			}
		}
		return responseBody;
	}

	/**
	 * @see org.apache.commons.httpclient.HttpMethod#getResponseBodyAsStream()
	 * @return
	 * @throws IOException
	 */
	public InputStream getDataAsStream() throws IOException {
		InputStream inStream = method.getResponseBodyAsStream();
		if (inStream == null) {
			return null;
		}

		if (getResponseHeader().gzip()) {
			inStream = new GZIPInputStream(inStream);
		}
		// count bytes for overall http-statistics
		return new ByteCountInputStream(inStream, incomingAccountingName);
	}

	/**
	 * Abort Download, e. g. when proxy connection was closed 
	 * Do not forget to call closeStream afterwards...
	 * 
	 */
	public void abort() {
		method.abort();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.anomic.http.HttpResponse#closeStream()
	 */
	public void closeStream() {
		method.releaseConnection();
		// statistics
		ConnectionInfo.removeConnection(method.hashCode());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.anomic.http.HttpResponse#getStatusLine()
	 */
	public String getStatusLine() {
		return getStatusCode() + " " + method.getStatusText();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.anomic.http.HttpResponse#getStatusCode()
	 */
	public int getStatusCode() {
		final int code = method.getStatusCode();
		assert code >= 100 && code <= 999 : "postcondition violated: StatusCode ("
				+ code + ") has not 3 digits!";
		return code;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.anomic.http.HttpResponse#getHttpVer()
	 */
	public String getHttpVer() {
		return method.getStatusLine().getHttpVersion();
	}

	/**
	 * sets the name for accounting incoming (loaded) bytes
	 * 
	 * @param accName
	 */
	public void setAccountingName(final String accName) {
		incomingAccountingName = accName;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#finalize()
	 */
    @Override
	protected void finalize() {
		closeStream();
	}
}
