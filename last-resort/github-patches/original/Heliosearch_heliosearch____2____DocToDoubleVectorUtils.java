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
package org.apache.lucene.classification.utils;

import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;

/**
 * utility class for converting Lucene {@link org.apache.lucene.document.Document}s to <code>Double</code> vectors.
 */
public class DocToDoubleVectorUtils {

  private DocToDoubleVectorUtils() {
    // no public constructors
  }

  /**
   * create a sparse <code>Double</code> vector given doc and field term vectors using local frequency of the terms in the doc
   * @param docTerms term vectors for a given document
   * @param fieldTerms field term vectors
   * @return a sparse vector of <code>Double</code>s as an array
   * @throws IOException in case accessing the underlying index fails
   */
  public static Double[] toSparseLocalFreqDoubleArray(Terms docTerms, Terms fieldTerms) throws IOException {
    TermsEnum fieldTermsEnum = fieldTerms.iterator(null);
    Double[] freqVector = null;
    if (docTerms != null) {
      freqVector = new Double[(int) fieldTerms.size()];
      int i = 0;
      TermsEnum docTermsEnum = docTerms.iterator(null);
      BytesRef term;
      while ((term = fieldTermsEnum.next()) != null) {
        TermsEnum.SeekStatus seekStatus = docTermsEnum.seekCeil(term);
        if (seekStatus.equals(TermsEnum.SeekStatus.END)) {
          docTermsEnum = docTerms.iterator(null);
        }
        if (seekStatus.equals(TermsEnum.SeekStatus.FOUND)) {
          long termFreqLocal = docTermsEnum.totalTermFreq(); // the total number of occurrences of this term in the given document
          freqVector[i] = Long.valueOf(termFreqLocal).doubleValue();
        }
        else {
          freqVector[i] = 0d;
        }
        i++;
      }
    }
    return freqVector;
  }

  /**
   * create a dense <code>Double</code> vector given doc and field term vectors using local frequency of the terms in the doc
   * @param docTerms term vectors for a given document
   * @return a dense vector of <code>Double</code>s as an array
   * @throws IOException in case accessing the underlying index fails
   */
  public static Double[] toDenseLocalFreqDoubleArray(Terms docTerms) throws IOException {
    Double[] freqVector = null;
    if (docTerms != null) {
        freqVector = new Double[(int) docTerms.size()];
        int i = 0;
        TermsEnum docTermsEnum = docTerms.iterator(null);

        while (docTermsEnum.next() != null) {
            long termFreqLocal = docTermsEnum.totalTermFreq(); // the total number of occurrences of this term in the given document
            freqVector[i] = Long.valueOf(termFreqLocal).doubleValue();
            i++;
        }
    }
    return freqVector;
}
}
