package com.saltstack.jenkins;

import java.io.IOException;
import java.util.logging.Logger;

import hudson.EnvVars;


class VmName {

    /*
     * The replacements order is replace last item first
     */

    static LINODE_MAX_SIZE = 32
    static RACKSPACE_MAX_SIZE = 256

    static private def replacements = [
        ['slt', 's'],
        ['dev', 'd'],
        ['cld', 'c'],
        ['lin', 'l'],
        ['salt', 'slt'],
        ['cloud', 'cld'],
        ['s-cloud', 'cld'],
        ['2015.2', '15_2'],
        ['2014.7', '14_7'],
        ['2014.1', '14_1'],
        ['devel', 'dev'],
        ['bs', 'bootstrap'],
        ['develop', 'devel'],
        ['salt-cloud', 's-cloud'],
        ['nightly', 'ntly'],
        ['freebsd', 'fbsd'],
        ['centos-', 'cent'],
        ['ubuntu', 'ubtu'],
        ['debian', 'deb'],
        ['fedora-', 'f'],
        ['opensuse', 'osuse'],
        ['linode', 'lin'],
        ['rackspace', 'rs'],
    ]

    static def generate(build, limit = 0) {
        LOGGER.info("Injecting SALT_VM_NAME");
        EnvVars build_env_vars = new EnvVars();
        try {
            build_env_vars = build.getEnvironment();
            String vm_name_prefix = build_env_vars.get("JENKINS_VM_NAME_PREFIX", "Z");
            String vm_name_suffix = build_env_vars.get("JOB_NAME").replace("/", "-").replace(".", "_");
            String build_number = build_env_vars.get("BUILD_NUMBER").padLeft(4, '0');
            String salt_vm_name = "${vm_name_prefix}-${vm_name_suffix}-${build_number}";

            if ( limit > 0 ) {
                def local_replacements = replacements.collect()
                while ( true ) {
                    if ( local_replacements.size() <= 0 ) {
                        break
                    }
                    def replacement = local_replacements.pop()
                    salt_vm_name = salt_vm_name.replace(replacement[0], replacement[1])
                    if ( salt_vm_name.size() <= limit ) {
                        break
                    }
                }
            }

            LOGGER.info("SALT_VM_NAME = ${salt_vm_name}");
            return salt_vm_name
        } catch (IOException e) {
            LOGGER.warning("Failed to inject SALT_VM_NAME: " + e.toString());
        } catch (InterruptedException e) {
            LOGGER.warning("Failed to inject SALT_VM_NAME: " + e.toString());
        }
    }

    private static final Logger LOGGER = Logger.getLogger(VmName.class.getName());
}
