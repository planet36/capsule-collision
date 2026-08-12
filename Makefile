SRC_DIR := src
OUT_DIR := out
CASES := test/cases.txt

SOURCES := $(shell find $(SRC_DIR) -name '*.java')

.PHONY: all build test clean

all: build

build: $(OUT_DIR)/.compiled

$(OUT_DIR)/.compiled: $(SOURCES)
	javac -Xlint:all -d $(OUT_DIR) $(SOURCES)
	@touch $@

test: build
	java -cp $(OUT_DIR) geom.CapsuleTestRunner $(CASES)

clean:
	rm -rf $(OUT_DIR)
