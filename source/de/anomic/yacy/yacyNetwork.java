// yacyNetwork.java 
// ----------------
// (C) 2007 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 04.07.2007 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2007-07-03 22:55:47 +0000 (Di, 03 Jul 2007) $
// $LastChangedRevision: 3950 $
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

package de.anomic.yacy;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.yacy.kelondro.order.Digest;

import org.apache.commons.httpclient.methods.multipart.Part;

import de.anomic.http.client.DefaultCharsetStringPart;
import de.anomic.kelondro.util.DateFormatter;
import de.anomic.search.Switchboard;
import de.anomic.search.SwitchboardConstants;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;

public class yacyNetwork {

	public static final boolean authentifyRequest(final serverObjects post, final serverSwitch env) {
		if ((post == null) || (env == null)) return false;
		
		// identify network
		final String unitName = post.get(SwitchboardConstants.NETWORK_NAME, yacySeed.DFLT_NETWORK_UNIT); // the network unit  
		if (!unitName.equals(env.getConfig(SwitchboardConstants.NETWORK_NAME, yacySeed.DFLT_NETWORK_UNIT))) {
			return false;
		}
        
		// check authentification method
		final String authentificationControl = env.getConfig("network.unit.protocol.control", "uncontrolled");
		if (authentificationControl.equals("uncontrolled")) return true;
		final String authentificationMethod = env.getConfig("network.unit.protocol.request.authentification.method", "");
		if (authentificationMethod.length() == 0) {
			return false;
		}
		if (authentificationMethod.equals("salted-magic-sim")) {
            // authentify the peer using the md5-magic
            final String salt = post.get("key", "");
            final String iam = post.get("iam", "");
            final String magic = env.getConfig("network.unit.protocol.request.authentification.essentials", "");
            final String md5 = Digest.encodeMD5Hex(salt + iam + magic);
			return post.get("magicmd5", "").equals(md5);
		}
		
		// unknown authentification method
		return false;
	}
	
	public static final List<Part> basicRequestPost(final Switchboard sb, final String targetHash, final String salt) {
        // put in all the essentials for routing and network authentification
		// generate a session key
        final ArrayList<Part> post = new ArrayList<Part>();
        post.add(new DefaultCharsetStringPart("key", salt));
        
        // just standard identification essentials
		post.add(new DefaultCharsetStringPart("iam", sb.peers.mySeed().hash));
		if (targetHash != null) post.add(new DefaultCharsetStringPart("youare", targetHash));
        
        // time information for synchronization
		post.add(new DefaultCharsetStringPart("mytime", DateFormatter.formatShortSecond(new Date())));
		post.add(new DefaultCharsetStringPart("myUTC", Long.toString(System.currentTimeMillis())));

        // network identification
        post.add(new DefaultCharsetStringPart(SwitchboardConstants.NETWORK_NAME, Switchboard.getSwitchboard().getConfig(SwitchboardConstants.NETWORK_NAME, yacySeed.DFLT_NETWORK_UNIT)));

        // authentification essentials
        final String authentificationControl = sb.getConfig("network.unit.protocol.control", "uncontrolled");
        final String authentificationMethod = sb.getConfig("network.unit.protocol.request.authentification.method", "");
        if ((authentificationControl.equals("controlled")) && (authentificationMethod.length() > 0)) {
            if (authentificationMethod.equals("salted-magic-sim")) {
                // generate an authentification essential using the salt, the iam-hash and the network magic
                final String magic = sb.getConfig("network.unit.protocol.request.authentification.essentials", "");
                final String md5 = Digest.encodeMD5Hex(salt + sb.peers.mySeed().hash + magic);
                post.add(new DefaultCharsetStringPart("magicmd5", md5));
            }
        }        
        
		return post;
	}
	
}
