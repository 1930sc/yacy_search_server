// wikiParser.java 
// ---------
// part of YaCy
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2007
// Created 22.02.2007
//
// This file is contributed by Franz Brausze
//
// $LastChangedDate: $
// $LastChangedRevision: $
// $LastChangedBy: $
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

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.regex.Matcher;

import de.anomic.data.wiki.tokens.DefinitionListToken;
import de.anomic.data.wiki.tokens.LinkToken;
import de.anomic.data.wiki.tokens.ListToken;
import de.anomic.data.wiki.tokens.SimpleToken;
import de.anomic.data.wiki.tokens.TableToken;
import de.anomic.data.wiki.tokens.Token;
import de.anomic.plasma.plasmaSwitchboard;

public class knwikiParser implements wikiParser {
	
	public Token[] tokens;
	private String[] BEs;
    private final plasmaSwitchboard sb;
    
    public knwikiParser(final plasmaSwitchboard sb) {
        this.sb = sb;
    }
	
	public static void main(final String[] args) {
		final String text = "===T<pre>itle===\n" +
				"==blubb== was ==ein '''shice'''==...och.bla\n" +
				"* ein \n" +
				"*==test=</pre>=\n" +
				"** doppelt\n" +
				"* ''tess*sst''\n" +
				"*** xyz\n" +
				"=]*** huch\n" +
				"* ehehe***\n" +
				"* blubb\n" +
				"bliblablo\n\n\n" +
				"* blubb\n" +
				"{|border=-1\n" +
				"|-\n" +
				"||bla|| blubb\n" +
				"|-\n" +
				"||align center|och||huch||\n" +
				"|}\n" +
				"\n" +
				"# bla\n" +
				"# blubb\n" +
				"'''''ehehehe''''', ne?!\n" +
				"[http://www/index.html,ne?!] -\n" +
				"[[Image:blubb|BLA]] ---- och\n" +
				" blubb1\n" +
				" blubb2\n" +
				":doppel-blubb[= huch =]\n" +
				";hier:da\n" +
				";dort:und so\n" +
				";;und:doppelt\n\n\n\n" +
                "[[Image:blubb|BLA]]";
		// text = "[=\n=]* bla";
		String t = "[=] ein fucking [= test =]-text[=,ne?!=] joa, [=alles=]wunderbar," +
				"[=denk ich=] mal =]";
		final long l = System.currentTimeMillis();
		t = new knwikiParser(null).parse((args.length > 0) ? args[0] : text, "localhost:8080");
        System.out.println("parsing time: " + (System.currentTimeMillis() - l) + " ms");
        System.out.println("--- --- ---");
        System.out.println(t);
	}
    
    public String transform(final String content) {
        return parse(content, null);
    }
    
    public String transform(final String content, final plasmaSwitchboard sb) {
        return parse(content, null);
    }
    
    public String transform(final byte[] content) throws UnsupportedEncodingException {
        return parse(new String(content, "UTF-8"), null);
    }
    
    public String transform(
            final byte[] content, final String encoding,
            final plasmaSwitchboard switchboard) throws UnsupportedEncodingException {
        return parse(new String(content, encoding), null);
    }
    
    public String transform(final byte[] content, final String encoding) throws UnsupportedEncodingException {
        return parse(new String(content, encoding), null);
    }
    
    public String transform(final byte[] text, final String encoding, final String publicAddress) throws UnsupportedEncodingException {
        return parse(new String(text, encoding), publicAddress);
    }
    
    public String transform(final String text, final String publicAddress) {
        return parse(text, publicAddress);
    }
	
	public String parse(String text, final String publicAddress) {
        tokens = new Token[] {
                new SimpleToken('=', '=', new String[][] { null, { "h2" }, { "h3" }, { "h4" } }, true),
                new SimpleToken('\'', '\'', new String[][] { null, { "i" }, { "b" }, null, { "b", "i" } }, false),
                new LinkToken((publicAddress == null) ? sb.webIndex.peers().mySeed().getPublicAddress() : publicAddress, "Wiki.html?page=", sb),
                new ListToken('*', "ul"),
                new ListToken('#', "ol"),
                new ListToken(':', "blockquote", null),
                new ListToken(' ', null, "tt", false),
                new DefinitionListToken(),
                new TableToken()
        };
        final ArrayList<String> r = new ArrayList<String>();
        for (int i = 0, k, j; i < tokens.length; i++)
            if (tokens[i].getBlockElementNames() != null)
                for (j = 0; j < tokens[i].getBlockElementNames().length; j++) {
                    if (tokens[i].getBlockElementNames()[j] == null) continue;
                    if ((k = tokens[i].getBlockElementNames()[j].indexOf(' ')) > 1) {
                        r.add(tokens[i].getBlockElementNames()[j].substring(0, k));
                    } else {
                        r.add(tokens[i].getBlockElementNames()[j]);
                    }
                }
        r.add("hr");
        BEs = r.toArray(new String[r.size()]);
        
        Text[] tt = Text.split2Texts(text, "[=", "=]");
        for (int i=0; i<tt.length; i+=2)
        	tt[i].setText(parseUnescaped(tt[i].getText()));
        text = Text.mergeTexts(tt);
        
        tt = Text.split2Texts(text, "<pre>", "</pre>");
        for (int i=0; i<tt.length; i+=2)
            tt[i].setText(replaceBRs(tt[i].getText()));
        return Text.mergeTexts(tt);
	}
	
	public String parseUnescaped(String text) {
		Token st;
		Matcher m;
		StringBuffer sb;
		for (int i=0; i<tokens.length; i++) {
			st = tokens[i];
			for (int j=0; j<st.getRegex().length; j++) {
				m = st.getRegex()[j].matcher(text);
				sb = new StringBuffer();
				while (m.find()) try {
					//System.out.print("found " + st.getClass().getSimpleName() +  ": " +
					//		m.group().replaceAll("\n", "\\\\n").replaceAll("\t", "    ") + ", ");
					if (!st.setText(m.group(), j)) {
					//	System.out.println("not usable");
						continue;
					//} else {
					//	System.out.println("usable");
					}
					m.appendReplacement(sb, (st.getMarkup() == null) ? m.group() : st.getMarkup());
				} catch (final wikiParserException e) {
                    m.appendReplacement(sb, st.getText());
                }
				text = new String(m.appendTail(sb));
			}
		}
		return text.replaceAll("----", "<hr />");
	}
	
	private String replaceBRs(final String text) {
		final StringBuilder sb = new StringBuilder(text.length());
		final String[] tt = text.split("\n");
		boolean replace;
		for (int i=0, j; i<tt.length; i++) {
			replace = true;
			for (j=0; j<BEs.length; j++)
				if (tt[i].endsWith(BEs[j] + ">")) { replace = false; break; }
			sb.append(tt[i]);
            if (i < tt.length - 1) {
                if (replace) sb.append("<br />");
                sb.append("\n");
            }
		}
		return new String(sb);
	}
	
	private static class Text {
		
		public static final String escapeNewLine = "@";
		
		private String text;
		private final boolean escaped;
		private final boolean nl;
		
		public Text(final String text, final boolean escaped, final boolean newLineBefore) {
			this.text = text;
			this.escaped = escaped;
			this.nl = newLineBefore;
        }
		
		public String setTextPlain(final String text) { return this.text = text; }
		public String setText(final String text) {
			if (this.nl)
				this.text = text.substring(escapeNewLine.length());
			else
				this.text = text;
			return this.text;
		}
		
		public String getTextPlain() { return this.text; }
		public String getText() {
			if (this.nl)
				return escapeNewLine + this.text;
			return this.text;
		}
		
		public String toString() { return this.text; }
		public boolean isEscaped() { return this.escaped; }
		public boolean isNewLineBefore() { return this.nl; }
        
		static Text[] split2Texts(final String text, final String escapeBegin, final String escapeEnd) {
			if (text == null) return null;
			if (text.length() < 2) return new Text[] { new Text(text, false, true) };
			
			final int startLen = escapeBegin.length();
            final int endLen = escapeEnd.length();
			final ArrayList<Text> r = new ArrayList<Text>();
			boolean escaped = text.startsWith(escapeBegin);
			if (escaped) r.add(new Text("", false, true));
			int i, j = 0;
			while ((i = text.indexOf((escaped) ? escapeEnd : escapeBegin, j)) > -1) {
				r.add(resolve2Text(text, escaped, (j > 0) ? j + ((escaped) ? startLen : endLen) : 0, i, escapeEnd));
				j = i;
				escaped = !escaped;
			}
			r.add(resolve2Text(text, escaped, (escaped) ? j : (j > 0) ? j + endLen : 0, -1, escapeEnd));
			return r.toArray(new Text[r.size()]);
		}
		
		private static Text resolve2Text(final String text, final boolean escaped, final int from, int to, final String escapeEnd) {
			if (to == -1) to = text.length();
			return new Text(
					text.substring(from, to),
					escaped,
					from < escapeEnd.length() + 2 || (!escaped && text.charAt(from - escapeEnd.length() - 1) == '\n'));
		}
		
		static String mergeTexts(final Text[] texts) {
			final StringBuilder sb = new StringBuilder(2000);
			for (int n=0; n < texts.length; n++)
				sb.append(texts[n].getTextPlain());
			return new String(sb);
		}
	}
}
