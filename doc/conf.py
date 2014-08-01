# -*- coding: utf-8 -*-
#
# Gatling documentation build configuration

import sys, os

##############
# Extensions #
##############

extensions = ['sphinx.ext.todo', 'sphinx.ext.extlinks']
todo_include_todos = True
extlinks = {
  "issue" : ("https://github.com/gatling/gatling/issues/%s", "#"),
  "version" : ("https://github.com/gatling/gatling/issues?milestone=%s&state=closed", None)
}

################
# Project info #
################

project = u'Gatling'
copyright = u'2013, eBusiness Information'
version = '1.5.6'
release = version

####################
# General settings #
####################

master_doc = 'index'
add_function_parentheses = False
highlight_language = 'scala'
exclude_patterns = ['_build']
nitpicky = True

###############
# HTML output #
###############

html_theme = 'gatling'
html_theme_path = ['_sphinx/themes']
html_title = "Gatling documentation"
html_domain_indices = False
html_use_index = False
html_show_sourcelink = False
html_show_sphinx = False
htmlhelp_basename = 'Gatlingdoc'
