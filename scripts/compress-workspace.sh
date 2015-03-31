#!/bin/sh
echo '>>>>>>>>>>>>>> Compress Workspace >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>'
find . -not -name workspace.cpio.xz -not -name *.log | cpio -o | xz > workspace.cpio.xz
echo '<<<<<<<<<<<<<< Compress Workspace <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<'
