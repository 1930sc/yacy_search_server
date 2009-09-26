//User_p.java 
//-----------------------
//part of the AnomicHTTPD caching proxy
//(C) by Michael Peter Christen; mc@yacy.net
//first published on http://www.anomic.de
//Frankfurt, Germany, 2004
//
//This File is contributed by Alexander Schier
//
//$LastChangedDate$
//$LastChangedRevision$
//$LastChangedBy$
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

//You must compile this file with
//javac -classpath .:../Classes Message.java
//if the shell's current path is HTROOT

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;

import de.anomic.data.userDB;
import de.anomic.http.metadata.RequestHeader;
import de.anomic.http.server.HTTPDemon;
import de.anomic.kelondro.order.Base64Order;
import de.anomic.kelondro.order.Digest;
import de.anomic.search.Switchboard;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;

public class ConfigAccounts_p {
    
    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final serverObjects prop = new serverObjects();
        final Switchboard sb = Switchboard.getSwitchboard();
        userDB.Entry entry=null;

        // admin password
        boolean localhostAccess = sb.getConfigBool("adminAccountForLocalhost", false);
        if ((post != null) && (post.containsKey("setAdmin"))) {
            localhostAccess = post.get("access", "").equals("localhost");
            final String user   = (post == null) ? "" : post.get("adminuser", "");
            final String pw1    = (post == null) ? "" : post.get("adminpw1", "");
            final String pw2    = (post == null) ? "" : post.get("adminpw2", "");

            // may be overwritten if new password is given
            if ((user.length() > 0) && (pw1.length() > 3) && (pw1.equals(pw2))) {
                // check passed. set account:
                env.setConfig(HTTPDemon.ADMIN_ACCOUNT_B64MD5, Digest.encodeMD5Hex(Base64Order.standardCoder.encodeString(user + ":" + pw1)));
                env.setConfig("adminAccount", "");
            }
            
            if (localhostAccess) {
                if (sb.crawlStacker.acceptLocalURLs()) {
                    // in this case it is not allowed to use a localhostAccess option
                    prop.put("commitIntranetWarning", 1);
                    localhostAccess = false;
                    sb.setConfig("adminAccountForLocalhost", false);
                } else {
                    sb.setConfig("adminAccountForLocalhost", true);
                    // if an localhost access is configured, check if a local password is given
                    // if not, set a random password
                    if (post != null && env.getConfig(HTTPDemon.ADMIN_ACCOUNT_B64MD5, "").length() == 0) {
                        // make a 'random' password
                        env.setConfig(HTTPDemon.ADMIN_ACCOUNT_B64MD5, "0000" + Digest.encodeMD5Hex(System.getProperties().toString() + System.currentTimeMillis()));
                        env.setConfig("adminAccount", "");
                    }
                }
            } else {
                sb.setConfig("adminAccountForLocalhost", false);
                if (env.getConfig(HTTPDemon.ADMIN_ACCOUNT_B64MD5, "").startsWith("0000")) {
                    // make shure that the user can still use the interface after a random password was set
                    env.setConfig(HTTPDemon.ADMIN_ACCOUNT_B64MD5, "");
                }
            }
        }
        
        if (env.getConfig(HTTPDemon.ADMIN_ACCOUNT_B64MD5, "").length() == 0 && !env.getConfigBool("adminAccountForLocalhost", false)) {
            prop.put("passwordNotSetWarning", 1);
        }
        
        prop.put("localhost.checked", (localhostAccess) ? 1 : 0);
        prop.put("account.checked", (localhostAccess) ? 0 : 1);
        prop.put("statusPassword", localhostAccess ? "0" : "1");
        prop.put("defaultUser", "admin");
        
        //default values
        prop.put("current_user", "newuser");
        prop.put("username", "");
        prop.put("firstname", "");
        prop.put("lastname", "");
        prop.put("address", "");
        prop.put("timelimit", "");
        prop.put("timeused", "");
        final String[] rightNames=userDB.Entry.RIGHT_NAMES.split(",");
        final String[] rights=userDB.Entry.RIGHT_TYPES.split(",");
        int i;
        for(i=0;i<rights.length;i++){
        		prop.put("rights_"+i+"_name", rights[i]);
        		prop.put("rights_"+i+"_friendlyName", rightNames[i]);
        		prop.put("rights_"+i+"_set", "0");
        }
        prop.put("rights", i);
        
        prop.put("users", "0");
        
        if (sb.userDB == null)
            return prop;
        
        if(post == null){
            //do nothing

            //user != current_user
            //user=from userlist
            //current_user = edited user
        } else if(post.containsKey("user") && !(post.get("user")).equals("newuser")){
            if(post.containsKey("change_user")){
                //defaults for newuser are set above                
                entry=sb.userDB.getEntry(post.get("user"));
                // program crashes if a submit with emty username was made on previous mask and the user clicked on the 
                // link: "If you want to manage more Users, return to the user page." (parameter "user" is empty)
                if (entry != null) {
                    //TODO: set username read-only in html
                    prop.putHTML("current_user", post.get("user"));
                    prop.putHTML("username", post.get("user"));
                    prop.putHTML("firstname", entry.getFirstName());
                    prop.putHTML("lastname", entry.getLastName());
                    prop.putHTML("address", entry.getAddress());
                    prop.put("timelimit", entry.getTimeLimit());
                    prop.put("timeused", entry.getTimeUsed());
                    for(i=0;i<rights.length;i++){
                        prop.put("rights_"+i+"_set", entry.hasRight(rights[i]) ? "1" : "0");
                    }
                    prop.put("rights", i);
                }
            }else if( post.containsKey("delete_user") && !(post.get("user")).equals("newuser") ){
                sb.userDB.removeEntry(post.get("user"));
            }
        } else if(post.containsKey("change")) { //New User / edit User
            prop.put("text", "0");
            prop.put("error", "0");

            final String username=post.get("username");
            final String pw1=post.get("password");
            final String pw2=post.get("password2");
            if(! pw1.equals(pw2)){
                prop.put("error", "2"); //PW does not match
                return prop;
            }
            final String firstName=post.get("firstname");
            final String lastName=post.get("lastname");
            final String address=post.get("address");
            final String timeLimit=post.get("timelimit");
            final String timeUsed=post.get("timeused");
            final HashMap<String, String> rightsSet=new HashMap<String, String>();
            for(i=0;i<rights.length;i++){
        	    		rightsSet.put(rights[i], post.containsKey(rights[i])&&(post.get(rights[i])).equals("on") ? "true" : "false");
            }
            final HashMap<String, String> mem=new HashMap<String, String>();
            if( post.get("current_user").equals("newuser")){ //new user
                
				if(!pw1.equals("")){ //change only if set
	                mem.put(userDB.Entry.MD5ENCODED_USERPWD_STRING, Digest.encodeMD5Hex(username+":"+pw1));
				}
				mem.put(userDB.Entry.USER_FIRSTNAME, firstName);
				mem.put(userDB.Entry.USER_LASTNAME, lastName);
				mem.put(userDB.Entry.USER_ADDRESS, address);
				mem.put(userDB.Entry.TIME_LIMIT, timeLimit);
				mem.put(userDB.Entry.TIME_USED, timeUsed);
				for(i=0;i<rights.length;i++)
					mem.put(rights[i], rightsSet.get(rights[i]));

                try{
                    entry=sb.userDB.createEntry(username, mem);
                    sb.userDB.addEntry(entry);
                    prop.putHTML("text_username", username);
                    prop.put("text", "1");
                }catch(final IllegalArgumentException e){
                    prop.put("error", "3");
                }
                
            } else { //edit user

                entry = sb.userDB.getEntry(username);
				if(entry != null){
	                try{
						if(! pw1.equals("")){
			                entry.setProperty(userDB.Entry.MD5ENCODED_USERPWD_STRING, Digest.encodeMD5Hex(username+":"+pw1));
						}
						entry.setProperty(userDB.Entry.USER_FIRSTNAME, firstName);
						entry.setProperty(userDB.Entry.USER_LASTNAME, lastName);
						entry.setProperty(userDB.Entry.USER_ADDRESS, address);
						entry.setProperty(userDB.Entry.TIME_LIMIT, timeLimit);
						entry.setProperty(userDB.Entry.TIME_USED, timeUsed);
						for(i=0;i<rights.length;i++)
							entry.setProperty(rights[i], rightsSet.get(rights[i]));
		            }catch (final IOException e){
					}
                }else{
					prop.put("error", "1");
				}
				prop.putHTML("text_username", username);
				prop.put("text", "2");
            }//edit user
			prop.putHTML("username", username);
        }
		
		//Generate Userlist
        final Iterator<userDB.Entry> it = sb.userDB.iterator(true);
        int numUsers=0;
        while(it.hasNext()){
            entry = it.next();
            if (entry == null) continue;
            prop.putHTML("users_"+numUsers+"_user", entry.getUserName());
            numUsers++;
        }
        prop.put("users", numUsers);

        // return rewrite properties
        return prop;
    }
}
