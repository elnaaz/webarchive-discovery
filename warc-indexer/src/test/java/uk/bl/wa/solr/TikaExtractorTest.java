/**
 * 
 */
package uk.bl.wa.solr;

/*
 * #%L
 * warc-indexer
 * %%
 * Copyright (C) 2013 - 2015 The UK Web Archive
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URL;

import org.junit.Before;
import org.junit.Test;

/**
 * @author Andrew Jackson <Andrew.Jackson@bl.uk>
 *
 */
public class TikaExtractorTest {

	private TikaExtractor tika;

	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
		tika = new TikaExtractor();
	}

	@Test
	public void testMonaLisa() throws Exception {
		File ml = new File(
				"src/test/resources/wikipedia-mona-lisa/Mona_Lisa.html");
		URL url = ml.toURI().toURL();
		SolrRecord solr = new SolrRecord();
		tika.extract(solr, url.openStream(), url.toString());
		System.out.println("SOLR " + solr.getSolrDocument().toString());
		String text = (String) solr.getField(SolrFields.SOLR_EXTRACTED_TEXT)
				.getValue();
		assertTrue("Text should contain this string!",
				text.contains("Mona Lisa"));
		assertFalse(
				"Text should NOT contain this string! (implies bad newline handling)",
				text.contains("encyclopediaMona"));
	}

}
