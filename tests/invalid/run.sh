#!/bin/bash

files=$(ls *.while)

for f in $files
do
    nf=$(echo $f | sed -e "s/while/sysout/")
    java -cp ../../src whilelang.Main $f > $nf
done
