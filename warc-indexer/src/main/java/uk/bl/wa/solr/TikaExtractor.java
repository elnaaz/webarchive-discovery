package uk.bl.wa.solr;

/*
 * #%L
 * warc-indexer
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2013 - 2014 The UK Web Archive
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

import java.io.FilterWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Date;
import java.util.List;

import org.apache.commons.io.input.BoundedInputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tika.Tika;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.io.CloseShieldInputStream;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.DublinCore;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.html.BoilerpipeContentHandler;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.restlet.data.MediaType;
import org.xml.sax.ContentHandler;

import uk.bl.wa.extract.Times;
import uk.bl.wa.util.Instrument;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;


/**
 * c.f. uk.bl.wap.tika.TikaDeepIdentifier
 * 
 * @author Andrew Jackson <Andrew.Jackson@bl.uk>
 *
 */
public class TikaExtractor {
	
	public static final String TIKA_PARSE_EXCEPTION = "Tika-Parse-Exception";

	private static Log log = LogFactory.getLog(TikaExtractor.class);
	
	/** Time to wait for Tika to complete before giving up: */
	private long parseTimeout;
	
	/**
	 *  MIME types to exclude from parsing:
	 *  e.g.
# Javascript/CSS excluded as irrelevant.
# Archives excluded as these seem to cause Java heap space errors with Tika.
mime_exclude = x-tar,x-gzip,bz,lz,compress,zip,javascript,css,octet-stream,image,video,audio
     *
     */
	private List<String> excludes;
	
	/** The actual Tika instance */
	private Tika tika;
	
	/** Maximum number of characters of text to pull out of any given resource: */
	private int max_text_length; 

	/** Whether or not to use the Boilerpipe boilerplate remover */
	private boolean useBoilerpipe;

	/** Extract all metadata? */
	private boolean extractAllMetadata;

	/* --- --- --- --- */
	
	public TikaExtractor() {
		this( ConfigFactory.load() );
	}

	/**
	 * 
	 * @param conf
	 */
	public TikaExtractor( Config conf ) {
		this.tika = new Tika();
		
		this.excludes = conf.getStringList( "warc.index.tika.exclude_mime" );
		log.info("Config: MIME exclude list: " + this.excludes);
		
		this.parseTimeout = conf.getLong( "warc.index.tika.parse_timeout");
		log.info("Config: Parser timeout (ms) " + parseTimeout);
		
		this.max_text_length = conf.getBytes( "warc.index.tika.max_text_length").intValue(); 
		log.info("Config: Maximum length of text to extract (characters) "+ this.max_text_length);
		
		this.extractAllMetadata = conf
				.getBoolean("warc.index.tika.extract_all_metadata");
		log.info("Config: extractAllMetadata "+this.extractAllMetadata);

		this.useBoilerpipe = conf.getBoolean("warc.index.tika.use_boilerpipe");
		log.info("Config: useBoilerpipe " + this.useBoilerpipe);

	}


	
	/**
	 * Override embedded document parser logic to prevent descent.
	 * 
	 * @author Andrew Jackson <Andrew.Jackson@bl.uk>
	 *
	 */
	public class NonRecursiveEmbeddedDocumentExtractor extends ParsingEmbeddedDocumentExtractor {

		/** Parse embedded documents? Defaults to FALSE */
		private boolean parseEmbedded = false;

		public NonRecursiveEmbeddedDocumentExtractor(ParseContext context) {
			super(context);
		}

		/* (non-Javadoc)
		 * @see org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor#shouldParseEmbedded(org.apache.tika.metadata.Metadata)
		 */
		@Override
		public boolean shouldParseEmbedded(Metadata metadata) {
			return this.parseEmbedded;
		}
		
		/**
		 * @return the parseEmbedded
		 */
		public boolean isParseEmbedded() {
			return parseEmbedded;
		}

		/**
		 * @param parseEmbedded the parseEmbedded to set
		 */
		public void setParseEmbedded(boolean parseEmbedded) {
			this.parseEmbedded = parseEmbedded;
		}

	}
	
	private NonRecursiveEmbeddedDocumentExtractor embedded = null;

	private int maxBytesToParser = -1;
	
	/**
	 * 
	 * @param solr
	 * @param is
	 * @param url
	 * @return
	 * @throws IOException
	 */
	@SuppressWarnings( "deprecation" )
	public SolrRecord extract( SolrRecord solr, InputStream is, String url ) throws IOException {

		// Set up the TikaInputStream:
		TikaInputStream tikainput = null;
		if( this.maxBytesToParser  > 0 ) {
			tikainput = TikaInputStream.get( new BoundedInputStream( new CloseShieldInputStream(is), maxBytesToParser ) );
		} else {
			tikainput = TikaInputStream.get( new CloseShieldInputStream(is) );
		}
		
		// Also pass URL as metadata to allow extension hints to work:
		Metadata metadata = new Metadata();
		if( url != null )
			metadata.set( Metadata.RESOURCE_NAME_KEY, url);

        final long detectStart = System.nanoTime();
		StringBuilder detected = new StringBuilder();
		try {
			DetectRunner detect = new DetectRunner(tika, tikainput, detected,
					metadata);
			Thread detectThread = new Thread( detect, Long.toString( System.currentTimeMillis() ) );
			detectThread.start();
			detectThread.join( 10000L );
			detectThread.interrupt();
		} catch( NoSuchFieldError e ) {
			// TODO Is this an Apache POI version issue?
			log.error( "Tika.detect(): " + e.getMessage() );
			addExceptionMetadata(metadata, new Exception("detect threw "+e.getClass().getCanonicalName()) );
		} catch( Exception e ) {
			log.error( "Tika.detect(): " + e.getMessage() );
			addExceptionMetadata(metadata, e);
		}
        Instrument.timeRel("WARCPayloadAnalyzers.analyze#tikasolrextract", "TikaExtractor.extract#detect", detectStart);

		
		// Only proceed if we have a suitable type:
		if( !this.checkMime( detected.toString() ) ) {
			if( "".equals(detected.toString())) {
				solr.addField( SolrFields.SOLR_CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM.toString() );
			} else {
				solr.addField( SolrFields.SOLR_CONTENT_TYPE, detected.toString() );
			}
			return solr;
		}
		
		// Context
		ParseContext context = new ParseContext();
		StringWriter content = new StringWriter();
		
		// Override the recursive parsing:
		if( embedded == null )
			embedded = new NonRecursiveEmbeddedDocumentExtractor(context);
		context.set( EmbeddedDocumentExtractor.class, embedded );
		
		try {
            final long parseStart = System.nanoTime();
			ParseRunner runner = new ParseRunner( tika.getParser(), tikainput, this.getHandler( content ), metadata, context );
			Thread parseThread = new Thread( runner, Long.toString( System.currentTimeMillis() ) );
			try {
				parseThread.start();
				parseThread.join( this.parseTimeout );
				parseThread.interrupt();
				parseThread.join(this.parseTimeout);
			} catch( OutOfMemoryError o ) {
				log.error( "TikaExtractor.parse() - OutOfMemoryError: " + o.getMessage() );
				addExceptionMetadata(metadata, new Exception("OutOfMemoryError"));
			} catch( RuntimeException r ) {
				log.error( "TikaExtractor.parse() - RuntimeException: " + r.getMessage() );
				addExceptionMetadata(metadata, r);
			}
            Instrument.timeRel("WARCPayloadAnalyzers.analyze#tikasolrextract",
                               "TikaExtractor.extract#parse", parseStart);

			// If there was a parse error, report it:
			solr.addField( SolrFields.PARSE_ERROR, metadata.get( TikaExtractor.TIKA_PARSE_EXCEPTION ) );

            final long extractStart = System.nanoTime();
			// Copy the body text, forcing a UTF-8 encoding:
			String output = new String( content.toString().getBytes( "UTF-8" ) );
			if( runner.complete || !output.equals( "" ) ) {
				if( output.length() > this.max_text_length ) {
					output = output.substring(0, this.max_text_length);
				}
				log.debug("Extracted text from: " + url);
				log.debug("Extracted text: " + StringUtils.left(output, 300));
				solr.setField( SolrFields.SOLR_EXTRACTED_TEXT, output );
				solr.setField( SolrFields.SOLR_EXTRACTED_TEXT_LENGTH, Integer.toString( output.length() ) );
			} else {
				//log.debug("Failed to extract any text from: "+url);
			}
			
			// Noisily report all metadata properties:
			/*
			 * for( String m : metadata.names() ) {
			 * log.info("For "+url.substring(url.length() - (int)
			 * Math.pow(url.length(),0.85))+": "+m+" -> "+metadata.get(m)); }
			 */
			
			// Attempt to record all metadata discovered:
			if (this.extractAllMetadata) {
				for (String m : metadata.names()) {
					// Ignore these as they are not very interesting:
					if (Metadata.RESOURCE_NAME_KEY.equalsIgnoreCase(m)
							|| "dc:title".equalsIgnoreCase(m)
							|| "title".equalsIgnoreCase(m)
							|| "description".equalsIgnoreCase(m)
							|| "keywords".equalsIgnoreCase(m)
							|| Metadata.CONTENT_ENCODING.equalsIgnoreCase(m)
							|| Metadata.CONTENT_LOCATION.equalsIgnoreCase(m)
							|| "ACTINICTITLE".equalsIgnoreCase(m)
							|| Metadata.CONTENT_TYPE.equalsIgnoreCase(m)) {
						continue;
					}
					// Record in the document, but trim big ones:
					String value = metadata.get(m);
					if (value != null && value.length() > 100) {
						value = value.substring(0, 100);
					}
					solr.addField(SolrFields.SOLR_TIKA_METADATA, m + "="
							+ value);
				}
			}

			// Also Pick out particular metadata:
			String contentType = metadata.get( Metadata.CONTENT_TYPE );
			solr.addField(SolrFields.SOLR_CONTENT_TYPE, contentType);
			solr.addField(SolrFields.SOLR_TITLE, metadata.get(DublinCore.TITLE));
			solr.addField(SolrFields.SOLR_DESCRIPTION,
					metadata.get(DublinCore.DESCRIPTION));
			solr.addField(SolrFields.SOLR_KEYWORDS, metadata.get("keywords"));
			solr.addField(SolrFields.SOLR_AUTHOR,
					metadata.get(DublinCore.CREATOR));
			solr.addField(SolrFields.CONTENT_ENCODING,
					metadata.get(Metadata.CONTENT_ENCODING));

			// Parse out any embedded date that can act as a created/modified date.
			String date = null;
			if( metadata.get( Metadata.CREATION_DATE ) != null)
				date = metadata.get( Metadata.CREATION_DATE );
			if( metadata.get( Metadata.DATE ) != null)
				date = metadata.get( Metadata.DATE );
			if( metadata.get( Metadata.MODIFIED ) != null)
				date = metadata.get( Metadata.MODIFIED );
			if( date != null ) {
				DateTimeFormatter df = ISODateTimeFormat.dateTimeParser();
				DateTime edate = null;
				try {
					edate = df.parseDateTime(date);
				} catch( IllegalArgumentException e ) {
					log.error( "Could not parse: "+ date );
				}
				if( edate == null ) {
					Date javadate = Times.extractDate(date);
					if( javadate != null )
						edate = new org.joda.time.DateTime( javadate );
				}
				if( edate != null ) {
					solr.addField( SolrFields.LAST_MODIFIED_YEAR, ""+edate.getYear() );
					DateTimeFormatter iso_df = ISODateTimeFormat.dateTimeNoMillis().withZone(DateTimeZone.UTC);
					// solr.getSolrDocument().setField(SolrFields.LAST_MODIFIED,
					// edate);
					solr.setField(SolrFields.LAST_MODIFIED, iso_df.print(edate));
				}
			}
			
			// Also look to record the software identifiers:
			
			// Look for generic xmp:CreatorTool
			solr.addField(SolrFields.GENERATOR, metadata.get("xmp:CreatorTool"));
			// For PDF, support other metadata tags:
			//solr.addField(SolrFields.GENERATOR, metadata.get( "creator" )); // This appears to be dc:creator i.e. author.
			solr.addField(SolrFields.GENERATOR, metadata.get("producer"));
			solr.addField(SolrFields.GENERATOR, metadata.get(Metadata.SOFTWARE));
			solr.addField(SolrFields.GENERATOR, metadata.get("software"));
			solr.addField(SolrFields.GENERATOR, metadata.get("Software"));
			solr.addField(SolrFields.GENERATOR, metadata.get("generator"));
			solr.addField(SolrFields.GENERATOR, metadata.get("Generator"));
			solr.addField(SolrFields.GENERATOR, metadata.get("ProgId"));
			
			// Application ID, MS Office only AFAICT, and the VERSION is only doc
			String software = null;
			if( metadata.get( Metadata.APPLICATION_NAME ) != null ) software = metadata.get( Metadata.APPLICATION_NAME );
			if( metadata.get( Metadata.APPLICATION_VERSION ) != null ) software += " "+metadata.get( Metadata.APPLICATION_VERSION);
			// Images, e.g. JPEG and TIFF, can have 'Software', 'tiff:Software',
			// PNGs have a 'tEXt tEXtEntry: keyword=Software, value=GPL Ghostscript 8.71'
			String png_textentry = metadata.get("tEXt tEXtEntry");
			if( png_textentry != null && png_textentry.contains("keyword=Software, value=") )
				software = png_textentry.replace("keyword=Software, value=", "");
			/* Some JPEGs have this:
	Jpeg Comment: CREATOR: gd-jpeg v1.0 (using IJG JPEG v62), default quality
	comment: CREATOR: gd-jpeg v1.0 (using IJG JPEG v62), default quality
			 */
			if( software != null ) {
				solr.addField(SolrFields.GENERATOR, software);
			}
            Instrument.timeRel("WARCPayloadAnalyzers.analyze#tikasolrextract",
                               "TikaExtractor.extract#extract", extractStart);

		} catch( Exception e ) {
			log.error( "TikaExtractor.extract(): " + e.getMessage() );
		}

        // TODO: This should probably be wrapped in a method-spanning try-finally to guarantee close
        if (tikainput != null) {
            try {
                tikainput.close();
            } catch (IOException e) {
                log.warn("Exception closing TikaInputStream. This leaves tmp-files: " +  e.getMessage());
            }
        }

		return solr;
	}

	private class ParseRunner implements Runnable {
		private Parser parser;
		private InputStream tikainput;
		private ContentHandler handler;
		private Metadata metadata;
		private ParseContext context;
		private boolean complete;

		public ParseRunner( Parser parser, InputStream tikainput, ContentHandler handler, Metadata metadata, ParseContext context ) {
			this.parser = parser;
			this.tikainput = tikainput;
			this.handler = handler;
			this.metadata = metadata;
			this.context = context;
			this.complete = false;
		}

		@Override
		public void run() {
			this.complete = false;
			try {
				this.parser.parse( this.tikainput, this.handler, this.metadata, this.context );
				this.complete = true;
			} catch( InterruptedIOException i ) {
				this.complete = false;
				log.error( "ParseRunner.run() Interrupted: " + i.getMessage() );
				addExceptionMetadata(metadata, i);
			} catch( Exception e ) {
				this.complete = false;
				log.error( "ParseRunner.run() Exception: " + ExceptionUtils.getRootCauseMessage(e));
				addExceptionMetadata(metadata, e);
			} finally {
			}
		}
		
	}

	private class DetectRunner implements Runnable {
		private Tika tika;
		private InputStream input;
		private StringBuilder mime;
		private Metadata metadata;

		public DetectRunner(Tika tika, InputStream input, StringBuilder mime,
				Metadata metadata) {
			this.tika = tika;
			this.input = input;
			this.mime = mime;
			this.metadata = metadata;
		}

		@Override
		public void run() {
			try {
				mime.append( this.tika.detect( this.input ) );
			} catch( NoSuchFieldError e ) {
				// Apache POI version issue?
				log.error("Tika.detect(): " + e.getMessage());
				addExceptionMetadata(metadata, new Exception(e));
			} catch( Exception e ) {
				log.error( "Tika.detect(): " + e.getMessage() );
				addExceptionMetadata(metadata, e);
			}
		}
	}
	
	/**
	 * Support storing the error message for analysis:
	 * 
	 * @param e
	 */
	public static void addExceptionMetadata( Metadata metadata, Exception e ) {
		Throwable t = ExceptionUtils.getRootCause(e);
		if ( t == null ) t = e;
		if ( t == null ) return;
	    metadata.set(TikaExtractor.TIKA_PARSE_EXCEPTION, t.getClass().getName()+": "+t.getMessage());
	}

	public ContentHandler getHandler( Writer out ) {
		// Set up the to-text handler
		ContentHandler ch = new BodyContentHandler(new SpaceTrimWriter(out));
		// Optionally wrap in the 'boilerpipe' boilerplate-remover:
		if (this.useBoilerpipe) {
			BoilerpipeContentHandler bpch = new BoilerpipeContentHandler(ch);
			bpch.setIncludeMarkup(false);
			ch = bpch;
		}
		// return ch;
		// Finally, wrap in a limited write-out to avoid hanging processing
		// very large or malformed streams.
		return new WriteOutContentHandler(ch, max_text_length);
	}
	
	public class SpaceTrimWriter extends FilterWriter {
		private boolean isStartSpace = true;
		private boolean lastCharWasSpace;
		private boolean includedNewline = false;

		public SpaceTrimWriter(Writer out) {
			super(out);
		}

		public void write(char[] cbuf, int off, int len) throws IOException {
			for (int i = off; i < len; i++)
				write(cbuf[i]);
		}

		public void write(String str, int off, int len) throws IOException {
			for (int i = off; i < len; i++)
				write(str.charAt(i));
		}

		public void write(int c) throws IOException {
			if (c == ' ' || c == '\n' || c == '\t') {
				lastCharWasSpace = true;
				if (c == '\n')
					includedNewline = true;
			} else {
				if (lastCharWasSpace) {
					if (!isStartSpace) {
						if (includedNewline) {
							out.write('\n');
						} else {
							out.write(' ');
						}
					}
					lastCharWasSpace = false;
					includedNewline = false;
				}
				isStartSpace = false;
				out.write(c);
			}
		}
	}
	
	private boolean checkMime( String mime ) {
		if( mime == null )
			return false;

		for( String exclude : excludes ) {
			if( mime.matches( ".*" +  exclude + ".*" ) ) {
				return false;
			}
		}
		return true;
	}
}
