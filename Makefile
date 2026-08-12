# SPDX-FileCopyrightText: Steven Ward
# SPDX-License-Identifier: MPL-2.0

SRC_DIR := src
OUT_DIR := out
CASES := test/cases.txt

SOURCES := $(shell find $(SRC_DIR) -name '*.java')

# A fixed heap that is touched up front keeps the collector from resizing or
# faulting in pages partway through a timed run.
BENCH_FLAGS := -Xms1g -Xmx1g -XX:+AlwaysPreTouch

.PHONY: all build test bench bench-noea clean

all: build

build: $(OUT_DIR)/.compiled

$(OUT_DIR)/.compiled: $(SOURCES)
	javac -Xlint:all -d $(OUT_DIR) $(SOURCES)
	@touch $@

test: build
	java -cp $(OUT_DIR) geom.CapsuleTestRunner $(CASES)

bench: build
	java $(BENCH_FLAGS) -cp $(OUT_DIR) geom.CapsuleBench $(BENCH_ARGS)

# The same benchmark with escape analysis off, which is what decides whether
# the Vec3 objects contains() allocates per call cost anything.
bench-noea: build
	java $(BENCH_FLAGS) -XX:-DoEscapeAnalysis -cp $(OUT_DIR) geom.CapsuleBench $(BENCH_ARGS)

clean:
	rm -rf $(OUT_DIR)
