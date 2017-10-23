package org.easydarwin.model;

import java.io.Serializable;
import java.util.List;

/**
 * Created by Zheming.xin on 2017/10/23.
 */

public class FormatSizeArray implements Serializable {
    private List<FormatSize> formats;

    public List<FormatSize> getFormats() {
        return formats;
    }

    public void setFormats(List<FormatSize> formats) {
        this.formats = formats;
    }

    public class FormatSize implements Serializable {
        private int index;
        private int type;
        private List<String> size;

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public int getType() {
            return type;
        }

        public void setType(int type) {
            this.type = type;
        }

        public List<String> getSize() {
            return size;
        }

        public void setSize(List<String> size) {
            this.size = size;
        }
    }
}
