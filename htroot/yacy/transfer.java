// transfer.java 
// -----------------------
// part of YaCy caching proxy
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004
// created 07.11.2005
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

import java.io.File;
import java.io.IOException;

import net.yacy.kelondro.order.Base64Order;
import net.yacy.kelondro.order.Digest;

import de.anomic.http.metadata.HeaderFramework;
import de.anomic.http.metadata.RequestHeader;
import de.anomic.kelondro.util.FileUtils;
import de.anomic.search.Switchboard;
import de.anomic.search.blockrank.CRDistribution;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;
import de.anomic.yacy.yacyNetwork;
import de.anomic.yacy.yacySeed;

public final class transfer {

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final Switchboard sb = (Switchboard) env;
        final serverObjects prop = new serverObjects();
        if ((post == null) || (env == null)) return prop;
        if (!yacyNetwork.authentifyRequest(post, env)) return prop;
        
        final String process   = post.get("process", "");  // permission or store
        //String key       = post.get("key", "");      // a transmission key from the client
        final String otherpeer = post.get("iam", "");      // identification of the client (a peer-hash)
        final String purpose   = post.get("purpose", "");  // declares how the file shall be treated
        final String filename  = post.get("filename", ""); // a name of a file without path
        //long   filesize  = Long.parseLong((String) post.get("filesize", "")); // the size of the file

        prop.put("process", "0");
        prop.put("response", "denied"); // reject is default and is overwritten if ok
        prop.put("process_access", "");
        prop.put("process_address", "");
        prop.put("process_protocol", "");
        prop.put("process_path", "");
        prop.put("process_maxsize", "0");

        if (sb.isRobinsonMode() || !sb.rankingOn) {
        	// in a robinson environment, do not answer. We do not do any transfer in a robinson cluster.
        	return prop;
        }

        final yacySeed otherseed = sb.peers.get(otherpeer);
        if ((otherseed == null) || (filename.indexOf("..") >= 0)) {
            // reject unknown peers: this does not appear fair, but anonymous senders are dangerous
            // reject paths that contain '..' because they are dangerous
            if (sb.getLog().isFine()) {
                if (otherseed == null) sb.getLog().logFine("RankingTransmission: rejected unknown peer '" + otherpeer + "', current IP " + header.get(HeaderFramework.CONNECTION_PROP_CLIENTIP, "unknown"));
                if (filename.indexOf("..") >= 0) sb.getLog().logFine("RankingTransmission: rejected wrong path '" + filename + "' from peer " + (otherseed == null ? "null" : otherseed.getName() + "/" + otherseed.getPublicAddress()) + ", current IP " + header.get(HeaderFramework.CONNECTION_PROP_CLIENTIP, "unknown"));
            }
            return prop;
        }
        
        final String otherpeerName = otherseed.hash + ":" + otherseed.getName();
        
        if (process.equals("permission")) {
            prop.put("process", "0");
            if (((purpose.equals("crcon")) && (filename.startsWith("CRG")) && (filename.endsWith(".cr.gz"))) || ((filename.startsWith("domlist")) && (filename.endsWith(".txt.gz") || filename.endsWith(".zip")))) {
                // consolidation of cr files
                //System.out.println("yacy/transfer:post=" + post.toString());
                //String cansendprotocol = (String) post.get("can-send-protocol", "http");
                final String access = Base64Order.enhancedCoder.encode(Digest.encodeMD5Raw(otherpeer + ":" + filename)) + ":" + Base64Order.enhancedCoder.encode(Digest.encodeMD5Raw("" + System.currentTimeMillis()));
                prop.put("response", "ok");
                prop.put("process_access", access);
                prop.put("process_address", sb.peers.mySeed().getPublicAddress());
                prop.put("process_protocol", "http");
                prop.put("process_path", "");  // currently empty; the store process will find a path
                prop.put("process_maxsize", "-1"); // if response is too big we return the size of the file
                sb.rankingPermissions.put(Digest.encodeMD5Hex(Base64Order.standardCoder.encodeString(access)), filename);
                if (sb.getLog().isFine()) sb.getLog().logFine("RankingTransmission: granted peer " + otherpeerName + " to send CR file " + filename);
            }
            return prop;
        }

        if (process.equals("store")) {
            prop.put("process", "1");
            if (purpose.equals("crcon")) {
                final String fileString = post.get("filename$file");
                final String accesscode = post.get("access", "");   // one-time authentication
                final String md5 = post.get("md5", "");   // one-time authentication
                //java.util.HashMap perm = sb.rankingPermissions;
                //System.out.println("PERMISSIONDEBUG: accesscode=" + accesscode + ", permissions=" + perm.toString());
                final String grantedFile = sb.rankingPermissions.get(accesscode);
                prop.put("process_tt", "");
                if ((grantedFile == null) || (!(grantedFile.equals(filename)))) {
                    // fraud-access of this interface
                    prop.put("response", "denied");
                    if (sb.getLog().isFine()) sb.getLog().logFine("RankingTransmission: denied " + otherpeerName + " to send CR file " + filename + ": wrong access code");
                } else {
                    sb.rankingPermissions.remove(accesscode); // not needed any more
                    final File path = new File(sb.rankingPath, CRDistribution.CR_OTHER);
                    path.mkdirs();
                    final File file = new File(path, filename);
                    try {
                        if (file.getCanonicalPath().startsWith(path.getCanonicalPath())){
                            FileUtils.copy(fileString.getBytes(), file);
                            final String md5t = Digest.encodeMD5Hex(file);
                            if (md5t.equals(md5)) {
                                prop.put("response", "ok");
                                if (sb.getLog().isFine()) sb.getLog().logFine("RankingTransmission: received from peer " + otherpeerName + " CR file " + filename);
                            } else {
                                prop.put("response", "transfer failure");
                                if (sb.getLog().isFine()) sb.getLog().logFine("RankingTransmission: transfer failure from peer " + otherpeerName + " for CR file " + filename);
                            }
                        }else{
                            //exploit?
                            prop.put("response", "io error");
                            return prop;
                        }
                    } catch (final IOException e) {
                        prop.put("response", "io error");
                    }
                }
            }
            return prop;
        }

        // wrong access
        if (sb.getLog().isFine()) sb.getLog().logFine("RankingTransmission: rejected unknown process " + process + ":" + purpose + " from peer " + otherpeerName);
        return prop;
    }

}
