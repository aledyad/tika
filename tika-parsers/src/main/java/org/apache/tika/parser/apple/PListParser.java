/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.parser.apple;

import com.dd.plist.NSArray;
import com.dd.plist.NSData;
import com.dd.plist.NSDate;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSNumber;
import com.dd.plist.NSObject;
import com.dd.plist.NSSet;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.XHTMLContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;


import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Parser for Apple's plist and bplist.  This is a wrapper around
 *       com.googlecode.plist:dd-plist
 */
public class PListParser extends AbstractParser {

    private static final String ARR = "array";
    private static final String DATA = "data";
    private static final String DATE = "date";
    private static final String DICT = "dict";
    private static final String KEY = "key";
    private static final String NUMBER = "number";
    private static final String PLIST = "plist";
    private static final String SET = "set";
    private static final String STRING = "string";


    private static final Set<MediaType> SUPPORTED_TYPES =
            Collections.singleton(MediaType.application("x-bplist"));
    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(InputStream stream, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {

        EmbeddedDocumentExtractor embeddedDocumentExtractor =
                EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
        NSObject rootObj = null;
        try {
            if (stream instanceof TikaInputStream && ((TikaInputStream) stream).hasFile()) {
                rootObj = PropertyListParser.parse(((TikaInputStream) stream).getFile());
            } else {
                rootObj = PropertyListParser.parse(stream);
            }
        } catch (PropertyListFormatException|ParseException|ParserConfigurationException e) {
            throw new TikaException("problem parsing root", e);
        }
        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
        State state = new State(xhtml, metadata, embeddedDocumentExtractor, df);
        xhtml.startDocument();
        xhtml.startElement(PLIST);
        parseObject(rootObj, state);
        xhtml.endElement(PLIST);
        xhtml.endDocument();
    }

    private void parseObject(NSObject obj, State state)
            throws SAXException, IOException {

        if (obj instanceof NSDictionary) {
            parseDict((NSDictionary)obj, state);
        } else if (obj instanceof NSArray) {
            NSArray nsArray = (NSArray)obj;
            state.xhtml.startElement(ARR);
            for (NSObject child : nsArray.getArray()) {
                parseObject(child, state);
            }
            state.xhtml.endElement(ARR);
        } else if (obj instanceof NSString) {
            state.xhtml.startElement(STRING);
            state.xhtml.characters(((NSString)obj).getContent());
            state.xhtml.endElement(STRING);
        } else if (obj instanceof NSNumber) {
            state.xhtml.startElement(NUMBER);
            state.xhtml.characters(((NSNumber) obj).toString());
            state.xhtml.endElement(NUMBER);
        } else if (obj instanceof NSData) {
            state.xhtml.startElement(DATA);
            handleData((NSData) obj, state);
            state.xhtml.endElement(DATA);
        } else if (obj instanceof NSDate) {
            state.xhtml.startElement(DATE);
            String dateString = state.dateFormat.format(((NSDate)obj).getDate());
            state.xhtml.characters(dateString);
            state.xhtml.endElement(DATE);
        } else if (obj instanceof NSSet) {
            state.xhtml.startElement(SET);
            parseSet((NSSet)obj, state);
            state.xhtml.endElement(SET);
        } else {
            throw new UnsupportedOperationException("don't yet support this type of object: "+obj.getClass());
        }
    }

    private void parseSet(NSSet obj, State state)
            throws SAXException, IOException {
        state.xhtml.startElement(SET);
        for (NSObject child : obj.allObjects()) {
            parseObject(child, state);
        }
        state.xhtml.endElement(SET);
    }

    private void parseDict(NSDictionary obj, State state)
            throws SAXException, IOException {
        state.xhtml.startElement(DICT);
        for (Map.Entry<String, NSObject> mapEntry : obj.getHashMap().entrySet()) {
            String key = mapEntry.getKey();
            NSObject value = mapEntry.getValue();
            state.xhtml.element(KEY, key);
            parseObject(value, state);
        }
        state.xhtml.endElement(DICT);
    }

    private void handleData(NSData value, State state) throws IOException,
            SAXException {
        state.xhtml.characters(value.getBase64EncodedData());
        Metadata embeddedMetadata = new Metadata();
        if (! state.embeddedDocumentExtractor.shouldParseEmbedded(embeddedMetadata)) {
            return;
        }

        try (TikaInputStream tis = TikaInputStream.get(value.bytes())) {
            state.embeddedDocumentExtractor.parseEmbedded(tis, state.xhtml, embeddedMetadata, false);
        }
    }

    private static class State {
        final XHTMLContentHandler xhtml;
        final Metadata metadata;
        final EmbeddedDocumentExtractor embeddedDocumentExtractor;
        final DateFormat dateFormat;

        public State(XHTMLContentHandler xhtml,
                     Metadata metadata,
                     EmbeddedDocumentExtractor embeddedDocumentExtractor,
                     DateFormat df) {
            this.xhtml = xhtml;
            this.metadata = metadata;
            this.embeddedDocumentExtractor = embeddedDocumentExtractor;
            this.dateFormat = df;
        }
    }
}
