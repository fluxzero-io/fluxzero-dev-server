/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import java.util.ArrayDeque;
import java.util.List;

/** Small in-memory tail of test process output, shared by console subscribers. */
final class TestOutput {
    private final ArrayDeque<Line> lines=new ArrayDeque<>();
    private long sequence;
    synchronized void add(String module,String text) {
        text=text.replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]", "");
        if(text.length()>2000)text=text.substring(0,2000)+"…";
        lines.add(new Line(++sequence,module,text));
        while(lines.size()>200)lines.removeFirst();
    }
    synchronized void clear(){lines.clear();}
    synchronized List<Line> snapshot(){return List.copyOf(lines);}
    record Line(long sequence,String module,String text) {}
}
