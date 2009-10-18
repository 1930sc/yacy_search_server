// wikiCode.java 
// -------------------------------------
// part of YACY
//
// (C) 2005, 2006 by Alexander Schier, Marc Nause, Franz Brausze
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

package de.anomic.data.wiki;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.yacy.document.parser.html.CharacterCoding;

import de.anomic.server.serverCore;

/** This class provides methods to handle texts that have been posted in the yacyWiki or other
 * parts of YaCy that use this class, like the blog or the profile.
 *
 * @author Alexander Schier [AS], Franz Brausze [FB], Marc Nause [MN]
 */
public class wikiCode extends abstractWikiParser implements wikiParser {

    /* Table properties */
    private static final String[] tps = {"rowspan", "colspan", "vspace", "hspace", "cellspacing", "cellpadding", "border"};
    private static final Map<String, String[]> ps = new HashMap<String, String[]>();

    /* possible tags for headlines */
    private static final String[] headlineTags = new String[]{"====", "===", "=="};

    static {
        /* Arrays must be sorted since Array.searchBinary() is used later. For more info see:
         * http://java.sun.com/javase/6/docs/api/java/util/Arrays.html#binarySearch(T[], T, java.util.Comparator)
         */
        Arrays.sort(headlineTags);
        Arrays.sort(tps);
        String[] array;
        Arrays.sort(array = new String[]{"void", "above", "below", "hsides", "lhs", "rhs", "vsides", "box", "border"});
        ps.put("frame", array);
        Arrays.sort(array = new String[]{"none", "groups", "rows", "cols", "all"});
        ps.put("rules", array);
        Arrays.sort(array = new String[]{"top", "middle", "bottom", "baseline"});
        ps.put("valign", array);
        Arrays.sort(array = new String[]{"left", "right", "center"});
        ps.put("align", array);
    }

    private String numListLevel = "";
    private String ListLevel = "";
    private String defListLevel = "";
    private boolean cellprocessing = false;     //needed for prevention of double-execution of replaceHTML
    private boolean defList = false;            //needed for definition lists
    private boolean escape = false;             //needed for escape
    private boolean escaped = false;            //needed for <pre> not getting in the way
    private boolean newrowstart = false;        //needed for the first row not to be empty
    private boolean nolist = false;             //needed for handling of [= and <pre> in lists
    private boolean preformatted = false;       //needed for preformatted text
    private boolean preformattedSpan = false;   //needed for <pre> and </pre> spanning over several lines
    private boolean replacedHTML = false;       //indicates if method replaceHTML has been used with line already
    private boolean table = false;              //needed for tables, because they reach over several lines
    private int preindented = 0;                //needed for indented <pre>s
    private final List<String> dirElements = new ArrayList<String>();    //list of headlines used to create diectory of page

    /** Constructor of the class wikiCode */
    public wikiCode(String address) {
        super(address);
    }

    protected String transform(
            final BufferedReader reader,
            final int length) throws IOException {
        final StringBuilder out = new StringBuilder(length);
        String line;
        while ((line = reader.readLine()) != null) {
            out.append(transformLine(line)).append(serverCore.CRLF_STRING);
        }
        return out.insert(0, directory()).toString();
    }

    /**
     * Processes tables in the wiki code.
     * @param input String which might or might not contain parts of a table.
     * @return String with wiki code of parts of table replaced by HTML code for table.
     */
    //[FB], changes by [MN]
    private String processTable(final String input) {
        //some variables that make it easier to change codes for the table
        String line = "";
        final String tableStart = "&#123;&#124;";                 // {|
        final String newLine = "&#124;-";                         // |-
        final String cellDivider = "&#124;&#124;";                // ||
        final String tableEnd = "&#124;&#125;";                   // |}
        final String attribDivider = "&#124;";                    // |
        final int lenTableStart = tableStart.length();
        final int lenCellDivider = cellDivider.length();
        final int lenTableEnd = tableEnd.length();
        final int lenAttribDivider = attribDivider.length();

        if ((input.startsWith(tableStart)) && (!table)) {
            table = true;
            newrowstart = true;
            line = "<table";
            if (input.trim().length() > lenTableStart) {
                line += parseTableProperties(input.substring(lenTableStart).trim()).toString();
            }
            line += ">";
        } else if (input.startsWith(newLine) && (table)) {          // new row
            if (!newrowstart) {
                line += "\t</tr>\n";
            } else {
                newrowstart = false;
            }
            line = line + "\t<tr>";
        } else if ((input.startsWith(cellDivider)) && (table)) {
            line += "\t\t<td";
            final int cellEnd = (input.indexOf(cellDivider, lenCellDivider) > 0) ? (input.indexOf(cellDivider, lenCellDivider)) : (input.length());
            int propEnd = input.indexOf(attribDivider, lenCellDivider);
            final int occImage = input.indexOf("[[Image:", lenCellDivider);
            final int occEscape = input.indexOf("[=", lenCellDivider);
            //If resultOf("[[Image:") is less than propEnd, that means that there is no
            //property for this cell, only an image. Without this, YaCy could get confused
            //by a | in [[Image:picture.png|alt-text]] or [[Image:picture.png|alt-text]]
            //Same for [= (part of [= =])
            if ((propEnd > lenCellDivider) && ((occImage > propEnd) || (occImage < 0)) && ((occEscape > propEnd) || (occEscape < 0))) {
                propEnd = input.indexOf(attribDivider, lenCellDivider) + lenAttribDivider;
            } else {
                propEnd = cellEnd;
            }
            // both point at same place => new line
            if (propEnd == cellEnd) {
                propEnd = lenCellDivider;
            } else {
                line += parseTableProperties(input.substring(lenCellDivider, propEnd - lenAttribDivider).trim()).toString();
            }
            // quick&dirty fix [MN]
            if (propEnd > cellEnd) {
                propEnd = lenCellDivider;
            }
            table = false;
            cellprocessing = true;
            line += ">" + processTable(input.substring(propEnd, cellEnd).trim()) + "</td>";
            table = true;
            cellprocessing = false;
            if (cellEnd < input.length()) {
                line += "\n" + processTable(input.substring(cellEnd));
            }
        } else if (input.startsWith(tableEnd) && (table)) {          // Table end
            table = false;
            line += "\t</tr>\n</table>" + input.substring(lenTableEnd);
        } else {
            line = input;
        }
        return line;
    }

    // contributed by [MN], changes by [FB]
    /** This method takes possible table properties and tests if they are valid.
     * Valid in this case means if they are a property for the table, tr or td
     * tag as stated in the HTML Pocket Reference by Jennifer Niederst (1st edition)
     * The method is important to avoid XSS attacks on the wiki via table properties.
     * @param properties A string that may contain several table properties and/or junk.
     * @return A string that only contains table properties.
     */
    private StringBuilder parseTableProperties(final String properties) {
        final String[] values = properties.replaceAll("&quot;", "").split("[= ]");     //splitting the string at = and blanks
        final StringBuilder sb = new StringBuilder(properties.length());
        String key, value;
        String[] posVals;
        final int numberofvalues = values.length;
        for (int i = 0; i < numberofvalues; i++) {
            key = values[i].trim();
            if (key.equals("nowrap")) {
                addPair("nowrap", "nowrap", sb);
            } else if (i + 1 < numberofvalues) {
                value = values[++i].trim();
                if ((key.equals("summary")) ||
                        (key.equals("bgcolor") && value.matches("#{0,1}[0-9a-fA-F]{1,6}|[a-zA-Z]{3,}")) ||
                        ((key.equals("width") || key.equals("height")) && value.matches("\\d+%{0,1}")) ||
                        ((posVals = ps.get(key)) != null && Arrays.binarySearch(posVals, value) >= 0) ||
                        (Arrays.binarySearch(tps, key) >= 0 && value.matches("\\d+"))) {
                    addPair(key, value, sb);
                }
            }
        }
        return sb;
    }

    private StringBuilder addPair(final String key, final String value, final StringBuilder sb) {
        return sb.append(" ").append(key).append("=\"").append(value).append("\"");
    }

    /** This method processes ordered lists.
     */
    private String orderedList(String result) {
        if (!nolist) {    //lists only get processed if not forbidden (see code for [= and <pre>). [MN]
            int p0 = 0;
            int p1 = 0;
            //# sorted Lists contributed by [AS]
            //## Sublist
            if (result.startsWith(numListLevel + "#")) { //more #
                p0 = result.indexOf(numListLevel);
                p1 = result.length();
                result = "<ol>" + serverCore.CRLF_STRING +
                        "<li>" +
                        result.substring(numListLevel.length() + 1, p1) +
                        "</li>";
                numListLevel += "#";
            } else if (numListLevel.length() > 0 && result.startsWith(numListLevel)) { //equal number of #
                p0 = result.indexOf(numListLevel);
                p1 = result.length();
                result = "<li>" +
                        result.substring(numListLevel.length(), p1) +
                        "</li>";
            } else if (numListLevel.length() > 0) { //less #
                int i = numListLevel.length();
                String tmp = "";

                while (!result.startsWith(numListLevel.substring(0, i))) {
                    tmp += "</ol>";
                    i--;
                }
                numListLevel = numListLevel.substring(0, i);
                p0 = numListLevel.length();
                p1 = result.length();

                if (numListLevel.length() > 0) {
                    result = tmp +
                            "<li>" +
                            result.substring(p0, p1) +
                            "</li>";
                } else {
                    result = tmp + result.substring(p0, p1);
                }
            }
        // end contrib [AS]
        }
        return result;
    }

    /** This method processes unordered lists.
     */
    //contributed by [AS] put into it's own method by [MN]
    private String unorderedList(String result) {
        if (!nolist) {    //lists only get processed if not forbidden (see code for [= and <pre>). [MN]
            int p0 = 0;
            int p1 = 0;
            //contributed by [AS]
            if (result.startsWith(ListLevel + "*")) { //more stars
                p0 = result.indexOf(ListLevel);
                p1 = result.length();
                result = "<ul>" + serverCore.CRLF_STRING +
                        "<li>" +
                        result.substring(ListLevel.length() + 1, p1) +
                        "</li>";
                ListLevel += "*";
            } else if (ListLevel.length() > 0 && result.startsWith(ListLevel)) { //equal number of stars
                p0 = result.indexOf(ListLevel);
                p1 = result.length();
                result = "<li>" +
                        result.substring(ListLevel.length(), p1) +
                        "</li>";
            } else if (ListLevel.length() > 0) { //less stars
                int i = ListLevel.length();
                String tmp = "";

                while (ListLevel.length() >= i && !result.startsWith(ListLevel.substring(0, i))) {
                    tmp += "</ul>";
                    i--;
                }
                p0 = ListLevel.length();
                if (i < p0) {
                    ListLevel = ListLevel.substring(0, i);
                    p0 = ListLevel.length();
                }
                p1 = result.length();

                if (ListLevel.length() > 0) {
                    result = tmp +
                            "<li>" +
                            result.substring(p0, p1) +
                            "</li>";
                } else {
                    result = tmp + result.substring(p0, p1);
                }
            }
        //end contrib [AS]
        }
        return result;
    }

    /** This method processes definition lists.
     */
    //contributed by [MN] based on unordered list code by [AS]
    private String definitionList(String result) {
        if (!nolist) {    //lists only get processed if not forbidden (see code for [= and <pre>). [MN]
            int p0 = 0;
            int p1 = 0;
            if (result.startsWith(defListLevel + ";")) { //more semicolons
                String dt = "";
                String dd = "";
                p0 = result.indexOf(defListLevel);
                p1 = result.length();
                final String resultCopy = result.substring(defListLevel.length() + 1, p1);
                if ((p0 = resultCopy.indexOf(":")) > 0) {
                    dt = resultCopy.substring(0, p0);
                    dd = resultCopy.substring(p0 + 1);
                    result = "<dl>" + "<dt>" + dt + "</dt>" + "<dd>" + dd;
                    defList = true;
                }
                defListLevel += ";";
            } else if (defListLevel.length() > 0 && result.startsWith(defListLevel)) { //equal number of semicolons
                String dt = "";
                String dd = "";
                p0 = result.indexOf(defListLevel);
                p1 = result.length();
                final String resultCopy = result.substring(defListLevel.length(), p1);
                if ((p0 = resultCopy.indexOf(":")) > 0) {
                    dt = resultCopy.substring(0, p0);
                    dd = resultCopy.substring(p0 + 1);
                    result = "<dt>" + dt + "</dt>" + "<dd>" + dd;
                    defList = true;
                }
            } else if (defListLevel.length() > 0) { //less semicolons
                String dt = "";
                String dd = "";
                int i = defListLevel.length();
                String tmp = "";
                while (!result.startsWith(defListLevel.substring(0, i))) {
                    tmp += "</dd></dl>";
                    i--;
                }
                defListLevel = defListLevel.substring(0, i);
                p0 = defListLevel.length();
                p1 = result.length();
                if (defListLevel.length() > 0) {
                    final String resultCopy = result.substring(p0, p1);
                    if ((p0 = resultCopy.indexOf(":")) > 0) {
                        dt = resultCopy.substring(0, p0);
                        dd = resultCopy.substring(p0 + 1);
                        result = tmp + "<dt>" + dt + "</dt>" + "<dd>" + dd;
                        defList = true;
                    }
                } else {
                    result = tmp + result.substring(p0, p1);
                }
            }
        }
        return result;
    }

    /** This method processes links and images.
     */
    //contributed by [AS] except where stated otherwise
    private String linksAndImages(String result) {

        // create links
        String kl, kv, alt, align;
        int p;
        int p0 = 0;
        int p1 = 0;
        // internal links and images
        while ((p0 = result.indexOf("[[")) >= 0) {
            p1 = result.indexOf("]]", p0 + 2);
            if (p1 <= p0) {
                break;
            }
            kl = result.substring(p0 + 2, p1);

            // this is the part of the code that's responsible for images
            // contributed by [MN]
            if (kl.startsWith("Image:")) {
                alt = "";
                align = "";
                kv = "";
                kl = kl.substring(6);

                // are there any arguments for the image?
                if ((p = kl.indexOf("&#124;")) > 0) {
                    kv = kl.substring(p + 6);
                    kl = kl.substring(0, p);
                    // if there are 2 arguments, write them into ALIGN and ALT
                    if ((p = kv.indexOf("&#124;")) > 0) {
                        align = kv.substring(0, p);
                        //checking validity of value for align. Only non browser specific
                        //values get supported. Not supported: absmiddle, baseline, texttop
                        if ((align.equals("bottom")) ||
                                (align.equals("center")) ||
                                (align.equals("left")) ||
                                (align.equals("middle")) ||
                                (align.equals("right")) ||
                                (align.equals("top"))) {
                            align = " align=\"" + align + "\"";
                        } else {
                            align = "";
                        }
                        alt = " alt=\"" + kv.substring(p + 6) + "\"";
                    } // if there is just one, put it into ALT
                    else {
                        alt = " alt=\"" + kv + "\"";
                    }
                }

                // replace incomplete URLs and make them point to http://peerip:port/...
                // with this feature you can access an image in DATA/HTDOCS/share/yacy.gif
                // using the wikicode [[Image:share/yacy.gif]]
                // or an image DATA/HTDOCS/grafics/kaskelix.jpg with [[Image:grafics/kaskelix.jpg]]
                // you are free to use other sub-paths of DATA/HTDOCS
                if (kl.indexOf("://") < 1) {
                    kl = "http://" + super.address + "/" + kl;
                }

                result = result.substring(0, p0) + "<img src=\"" + kl + "\"" + align + alt + ">" + result.substring(p1 + 2);
            } // end contrib [MN]
            // if it's no image, it might be an internal link
            else {
                if ((p = kl.indexOf("&#124;")) > 0) {
                    kv = kl.substring(p + 6);
                    kl = kl.substring(0, p);
                } else {
                    kv = kl;
                }
                result = result.substring(0, p0) + "<a class=\"known\" href=\"Wiki.html?page=" + kl + "\">" + kv + "</a>" + result.substring(p1 + 2); // oob exception in append() !
            }
        }

        // external links
        while ((p0 = result.indexOf("[")) >= 0) {
            p1 = result.indexOf("]", p0 + 1);
            if (p1 <= p0) {
                break;
            }
            kl = result.substring(p0 + 1, p1);
            if ((p = kl.indexOf(" ")) > 0) {
                kv = kl.substring(p + 1);
                kl = kl.substring(0, p);
            } // No text for the link? -> <a href="http://www.url.com/">http://www.url.com/</a>
            else {
                kv = kl;
            }
            // replace incomplete URLs and make them point to http://peerip:port/...
            // with this feature you can access a file at DATA/HTDOCS/share/page.html
            // using the wikicode [share/page.html]
            // or a file DATA/HTDOCS/www/page.html with [www/page.html]
            // you are free to use other sub-paths of DATA/HTDOCS
            if (kl.indexOf("://") < 1) {
                kl = "http://" + super.address + "/" + kl;
            }
            result = result.substring(0, p0) + "<a class=\"extern\" href=\"" + kl + "\">" + kv + "</a>" + result.substring(p1 + 1);
        }
        return result;
    }

    /** This method handles the preformatted tags <pre> </pre> */
    //contributed by [MN]
    private String preformattedTag(String result) {
        int p0 = 0;
        int p1 = 0;
        //both <pre> and </pre> in the same line
        if (((p0 = result.indexOf("&lt;pre&gt;")) >= 0) && ((p1 = result.indexOf("&lt;/pre&gt;")) > 0) && (!(escaped))) {
            if (p0 < p1) {
                String preformattedText = "<pre style=\"border:dotted;border-width:thin\">" + result.substring(p0 + 11, p1) + "</pre>";
                preformattedText = preformattedText.replaceAll("!pre!", "!pre!!");
                result = transformLine(result.substring(0, p0).replaceAll("!pre!", "!pre!!") + "!pre!txt!" + result.substring(p1 + 12).replaceAll("!pre!", "!pre!!"));
                result = result.replaceAll("!pre!txt!", preformattedText);
                result = result.replaceAll("!pre!!", "!pre!");
            } //handles cases like <pre><pre> </pre></pre> <pre> </pre> that would cause an exception otherwise
            else {
                preformatted = true;
                final String temp1 = transformLine(result.substring(0, p0 - 1).replaceAll("!tmp!", "!tmp!!") + "!tmp!txt!");
                nolist = true;
                final String temp2 = transformLine(result.substring(p0));
                nolist = false;
                result = temp1.replaceAll("!tmp!txt!", temp2);
                result = result.replaceAll("!tmp!!", "!tmp!");
                preformatted = false;
            }
        } //start <pre>
        else if (((p0 = result.indexOf("&lt;pre&gt;")) >= 0) && (!preformattedSpan) && (!escaped)) {
            preformatted = true;    //prevent surplus line breaks
            String bq = "";  //gets filled with <blockquote>s as needed
            String preformattedText = "<pre style=\"border:dotted;border-width:thin\">" + result.substring(p0 + 11);
            preformattedText = preformattedText.replaceAll("!pre!", "!pre!!");
            //taking care of indented lines
            while (result.substring(preindented, p0).startsWith(":")) {
                preindented++;
                bq = bq + "<blockquote>";
            }
            result = transformLine(result.substring(preindented, p0).replaceAll("!pre!", "!pre!!") + "!pre!txt!");
            result = bq + result.replaceAll("!pre!txt!", preformattedText);
            result = result.replaceAll("!pre!!", "!pre!");
            preformattedSpan = true;
        } //end </pre>
        else if (((p0 = result.indexOf("&lt;/pre&gt;")) >= 0) && (preformattedSpan) && (!escaped)) {
            preformattedSpan = false;
            String bq = ""; //gets filled with </blockquote>s as needed
            String preformattedText = result.substring(0, p0) + "</pre>";
            preformattedText = preformattedText.replaceAll("!pre!", "!pre!!");
            //taking care of indented lines
            while (preindented > 0) {
                bq = bq + "</blockquote>";
                preindented--;
            }
            result = transformLine("!pre!txt!" + result.substring(p0 + 12).replaceAll("!pre!", "!pre!!"));
            result = result.replaceAll("!pre!txt!", preformattedText) + bq;
            result = result.replaceAll("!pre!!", "!pre!");
            preformatted = false;
        } //Getting rid of surplus </pre>
        else if (((p0 = result.indexOf("&lt;/pre&gt;")) >= 0) && (!preformattedSpan) && (!escaped)) {
            while ((p0 = result.indexOf("&lt;/pre&gt;")) >= 0) {
                result = result.substring(0, p0) + result.substring(p0 + 12);
            }
            result = transformLine(result);
        }
        return result;
    }

    /** This method creates a directory for a wiki page.
     * @return directory of the wiki
     */
    //method contributed by [MN]
    private StringBuilder directory() {
        StringBuilder directory = new StringBuilder();
        String element;
        int s = 0;
        int level = 1;
        int level1 = 0;
        int level2 = 0;
        int level3 = 0;
        int doubles = 0;
        String anchorext = "";
        if ((s = dirElements.size()) > 2) {
            directory.append("<table><tr><td><div class=\"WikiTOCBox\">\n");
            for (int i = 0; i < s; i++) {
                if (i >= dirElements.size()) {
                    break;
                }
                element = dirElements.get(i);
                if (element == null) continue;
                //counting double headlines
                doubles = 0;
                for (int j = 0; j < i; j++) {
                    if (j >= dirElements.size()) {
                        break;
                    }
                    String d = dirElements.get(j);
                    if (d == null || d.length() < 1) {
                        continue;
                    }
                    String a = d.substring(1).replaceAll(" ", "_").replaceAll("[^a-zA-Z0-9_]", "");
                    String b = element.substring(1).replaceAll(" ", "_").replaceAll("[^a-zA-Z0-9_]", "");
                    if (a.equals(b)) {
                        doubles++;
                    }
                }
                //if there are doubles, create anchorextension
                if (doubles > 0) {
                    anchorext = "_" + (doubles + 1);
                }

                if (element.startsWith("3")) {
                    if (level < 3) {
                        level = 3;
                        level3 = 0;
                    }
                    level3++;
                    final String temp = element.substring(1);
                    element = level1 + "." + level2 + "." + level3 + " " + temp;
                    directory.append("&nbsp;&nbsp;&nbsp;&nbsp;<a href=\"#");
                    directory.append(temp.replaceAll(" ", "_").replaceAll("[^a-zA-Z0-9_]", ""));
                    directory.append(anchorext);
                    directory.append("\" class=\"WikiTOC\">");
                    directory.append(element);
                    directory.append("</a><br />\n");
                } else if (element.startsWith("2")) {
                    if (level == 1) {
                        level2 = 0;
                        level = 2;
                    }
                    if (level == 3) {
                        level = 2;
                    }
                    level2++;
                    final String temp = element.substring(1);
                    element = level1 + "." + level2 + " " + temp;
                    directory.append("&nbsp;&nbsp;<a href=\"#");
                    directory.append(temp.replaceAll(" ", "_").replaceAll("[^a-zA-Z0-9_]", ""));
                    directory.append(anchorext);
                    directory.append("\" class=\"WikiTOC\">");
                    directory.append(element);
                    directory.append("</a><br />\n");
                } else if (element.startsWith("1")) {
                    if (level > 1) {
                        level = 1;
                        level2 = 0;
                        level3 = 0;
                    }
                    level1++;
                    final String temp = element.substring(1);
                    element = level1 + ". " + temp;
                    directory.append("<a href=\"#");
                    directory.append(temp.replaceAll(" ", "_").replaceAll("[^a-zA-Z0-9_]", ""));
                    directory.append(anchorext + "\" class=\"WikiTOC\">");
                    directory.append(element);
                    directory.append("</a><br />\n");
                }
                anchorext = "";
            }
            directory.append("</div></td></tr></table>\n");
        }
        if (!dirElements.isEmpty()) {
            dirElements.clear();
        }
        return directory;
    }

    /** Replaces two occurences of a substring in a string by a pair of strings if 
     * that substring occurs twice in the string. This method is not greedy! You'll
     * have to run it in a loop if you want to replace all occurences of the substring.
     * This method provides special treatment for headlines.
     * @param input the string that something is to be replaced in
     * @param pat substring to be replaced
     * @param repl1 string substring gets replaced by on uneven occurences
     * @param repl2 string substring gets replaced by on even occurences
     */
    //[MN]
    private String pairReplace(String input, final String pat, final String repl1, final String repl2) {
        String direlem = null;    //string to keep headlines until they get added to List dirElements
        int p0 = 0;
        int p1 = 0;
        final int l = pat.length();
        //replace pattern if a pair of the pattern can be found in the line
        if (((p0 = input.indexOf(pat)) >= 0) && ((p1 = input.indexOf(pat, p0 + l)) >= 0)) {
            //extra treatment for headlines
            if (Arrays.binarySearch(headlineTags, pat) >= 0) {
                //add anchor and create headline
                direlem = input.substring(p0 + l, p1);
                if (direlem != null) {
                    //counting double headlines
                    int doubles = 0;
                    for (int i = 0; i < dirElements.size(); i++) {
                        // no element with null value should ever be in directory
                        assert (dirElements.get(i) != null);

                        if (dirElements.size() > i && dirElements.get(i).substring(1).equals(direlem)) {
                            doubles++;
                        }
                    }
                    String anchor = direlem.replaceAll(" ", "_").replaceAll("[^a-zA-Z0-9_]", ""); //replace blanks with underscores and delete everything thats not a regular character, a number or _
                    //if there are doubles, add underscore and number of doubles plus one
                    if (doubles > 0) {
                        anchor = anchor + "_" + (doubles + 1);
                    }
                    input = input.substring(0, p0) + "<a name=\"" + anchor + "\"></a>" + repl1 +
                            direlem + repl2 + input.substring(p1 + l);
                    //add headlines to list of headlines (so TOC can be created)
                    if (Arrays.binarySearch(headlineTags, pat) >= 0) {
                        dirElements.add((pat.length() - 1) + direlem);
                    }
                }
            } else {
                input = input.substring(0, p0) + repl1 +
                        (input.substring(p0 + l, p1)) + repl2 +
                        input.substring(p1 + l);
            }
        }
        //recursion if a pair of the pattern can still be found in the line
        if (((p0 = input.indexOf(pat)) >= 0) && (input.indexOf(pat, p0 + l) >= 0)) {
            input = pairReplace(input, pat, repl1, repl2);
        }
        return input;
    }

    /** Replaces wiki tags with HTML tags.
     * @param result a line of text
     * @return the line of text with HTML tags instead of wiki tags
     */
    public String transformLine(String result) {
        //If HTML has not bee replaced yet (can happen if method gets called in recursion), replace now!
        if (!replacedHTML || preformattedSpan) {
            result = CharacterCoding.unicode2html(result, true);
            replacedHTML = true;
        }

        //check if line contains preformatted symbols or if we are in a preformatted sequence already.
        if ((result.indexOf("&lt;pre&gt;") >= 0) || (result.indexOf("&lt;/pre&gt;") >= 0) || (preformattedSpan)) {
            result = preformattedTag(result);
        } else {

            //tables first -> wiki-tags in cells can be treated after that
            result = processTable(result);

            // format lines
            if (result.startsWith(" ")) {
                result = "<tt>" + result.substring(1) + "</tt>";
            }
            if (result.startsWith("----")) {
                result = "<hr />";
            }

            // citings contributed by [MN]
            if (result.startsWith(":")) {
                String head = "";
                String tail = "";
                while (result.startsWith(":")) {
                    head = head + "<blockquote>";
                    tail = tail + "</blockquote>";
                    result = result.substring(1);
                }
                result = head + result + tail;
            }
            // end contrib [MN]	

            // format headers
            result = pairReplace(result, "====", "<h4>", "</h4>");
            result = pairReplace(result, "===", "<h3>", "</h3>");
            result = pairReplace(result, "==", "<h2>", "</h2>");

            result = pairReplace(result, "'''''", "<b><i>", "</i></b>");
            result = pairReplace(result, "'''", "<b>", "</b>");
            result = pairReplace(result, "''", "<i>", "</i>");

            result = unorderedList(result);
            result = orderedList(result);
            result = definitionList(result);

            result = linksAndImages(result);

        }

        if (!preformatted) {
            replacedHTML = false;
        }
        if ((result.endsWith("</li>")) || (defList) || (escape) || (preformatted) || (table) || (cellprocessing)) {
            return result;
        }
        return result + "<br />";
    }
}
