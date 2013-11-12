package com.tonicsystems.jarjar;

import org.apache.commons.io.IOUtils;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.ZipFileSet;

import java.io.*;
import java.util.*;

/**
 * @author Jonathan Gamba
 *         Date: 10/15/13
 */
public class PackagerTask extends JarJarTask {

    private String outputFolder;
    private String outputFile;
    private String dotcmsJar;
    private String jspFolder;
    private String dotVersion;
    private boolean multipleJars;
    private boolean generateRulesFromParentFolder;
    private boolean skipDependenciesReplacement;
    private Vector<FileSet> filesets = new Vector<FileSet>();
    private List<Dependency> dependencies = new ArrayList<Dependency>();

    private boolean initialRenameServices;
    private boolean initialVerbose;

    public void execute () throws BuildException {

        log( "Executing Packager task....", Project.MSG_INFO );

        //Remember the initial values, they are clean after repackage each jar
        initialRenameServices = renameServices;
        initialVerbose = verbose;

        // Validate input
        if ( this.dotcmsJar == null ) {
            throw new IllegalArgumentException( "No dotCMS jar specified" );
        }
        File dotcmsJarFile = new File( this.dotcmsJar );
        log( "dotCMS jar location: " + dotcmsJarFile.getAbsolutePath(), Project.MSG_INFO );

        //++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //FIRST WE RUN THE INSPECTOR TO FIND DUPLICATED CLASSES
        //++++++++++++++++++++++++++++++++++++++++++++++++++++++

        //Create a formatter to read and display the results
        Formatter formatter = new PlainFormatter();
        formatter.setOutput( System.out );
        formatter.setDuplicatesOnly( true );

        //Create the inspector in order to analyze the given path
        Inspector inspector = new Inspector();
        inspector.addFormatter( formatter );

        Collection<File> toTransform = new ArrayList<File>();

        List<File> parentFolders = new ArrayList<File>();

        //Find all the jars define to repackage
        if ( filesets != null ) {
            for ( FileSet fileSet : filesets ) {

                if ( fileSet.getDirectoryScanner() != null && fileSet.getDirectoryScanner().getIncludedFiles() != null ) {
                    for ( String file : fileSet.getDirectoryScanner().getIncludedFiles() ) {

                        File fileToInspect = new File( fileSet.getDirectoryScanner().getBasedir().getAbsolutePath() + File.separator + file );

                        //Global list of jars to repackage
                        if ( !toTransform.contains( fileToInspect ) ) {
                            toTransform.add( fileToInspect );
                        }

                        if ( isGenerateRulesFromParentFolder() ) {
                            if ( !parentFolders.contains( fileToInspect.getParentFile() ) ) {
                                parentFolders.add( fileToInspect.getParentFile() );
                                inspector.inspect( fileToInspect.getParentFile() );
                            }
                        } else {
                            inspector.inspect( fileToInspect );
                        }
                    }
                }
            }
        }
        //inspector.report( "Duplicated classes" );//Generate a report

        log( "-----------------------------------------" );
        log( "Found " + inspector.getClassCount() + " unique classes" );
        log( "Found " + inspector.getDuplicateCount() + " duplicated classes" );

        /*if ( inspector.getDuplicateCount() > 0 ) {
            throw new IllegalStateException( "Found duplicated classes on the specified class path, you need to fix those duplicates " +
                    "in order to continue." );
        }*/

        //++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //PREPARE ALL THE JARS AND RULES TO APPLY
        //++++++++++++++++++++++++++++++++++++++++++++++++++++++
        HashMap<String, Rule> rulesToApply = new HashMap<String, Rule>();

        //Get all the classes we found
        HashMap<String, List<Inspector.PathInfo>> classes = inspector.getClasses();
        for ( String name : classes.keySet() ) {

            //Collection with the info of found for each class
            //List<Inspector.PathInfo> details = classes.get( name );
            //Inspector.PathInfo detail = details.get( 0 );//Grabbing the first one, could be more than one for duplicated classes
            //File jarFile = detail.base;

            //Handle some strings to use in the rules
            String packageName;
            if ( name.lastIndexOf( "." ) != -1 ) {
                //Get the package of the class (from my.package.myClassName to my.package)
                packageName = name.substring( 0, name.lastIndexOf( "." ) );
            } else {
                //On the root??, no package??
                continue;
            }

            //Create a name to be part of the resulting package name
            /*String jarNameForPackage = jarFile.getName().substring( 0, jarFile.getName().lastIndexOf( "." ) );*/
            String jarNameForPackage = "_" + getDotVersion() + "_";
            jarNameForPackage = jarNameForPackage.replaceAll( "-", "_" );
            jarNameForPackage = jarNameForPackage.replaceAll( "\\.", "_" );
            jarNameForPackage = jarNameForPackage.toLowerCase();

            //Create the rule for this class and add it to the list of rules for this jar
            String pattern = packageName + ".**";//Example: "org.apache.xerces.dom.**"
            String result = "com.dotcms.repackage." + jarNameForPackage + "." + packageName + ".@1";

            Rule rule = new Rule();
            rule.setPattern( pattern );
            rule.setResult( result );

            //Global list of rules to apply
            if ( !rulesToApply.containsKey( pattern + result ) ) {
                rulesToApply.put( pattern + result, rule );
            }
        }

        //++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //NOW REPACKAGE THE JARS
        //++++++++++++++++++++++++++++++++++++++++++++++++++++++

        //-------------------------------
        //SOME LOGGING
        log( "-----------------------------------------" );
        log( "Jars to repackage: " + toTransform.size() );
        logRules( rulesToApply.values() );
        logJars( toTransform );

        //And finally repackage the jars but how we do it depends on if we want them all on a single jar or multiples
        if ( isMultipleJars() ) {

            //Repackage jar by jar
            for ( File jar : toTransform ) {

                //Repackaging this single jar
                Collection<File> transform = new ArrayList<File>();
                transform.add( jar );

                File outJar = new File( getOutputFolder() + File.separator + jar.getName() );
                generate( outJar, rulesToApply.values(), transform );
            }

        } else {
            //Repackage all the jars in a single jar
            File outJar = new File( getOutputFolder() + File.separator + getOutputFile() );
            generate( outJar, rulesToApply.values(), toTransform );
        }

        if ( isSkipDependenciesReplacement() ) {
            return;
        }

        //++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //APPLY THE SAME RULES TO GIVEN FILES,JAR's,  XML's, .PROPERTIES, ETC...
        //++++++++++++++++++++++++++++++++++++++++++++++++++++++

        //Add the dotcms jar as a dependency we need to check and modify
        Dependency dotcmsJarDependency = new Dependency();
        dotcmsJarDependency.setPath( this.dotcmsJar );
        dependencies.add( dotcmsJarDependency );

        for ( Dependency dependency : dependencies ) {

            //The file to check and probably to modify
            String filePath = dependency.getPath();

            //Verify if we are going to check for dependencies on another jars or files
            if ( isJarName( filePath ) ) {

                //++++++++++++++++++++++++++++++++++++++++++++++++++++++
                //CHANGE THE OLD REFERENCES IN DEPENDENT JARS
                //++++++++++++++++++++++++++++++++++++++++++++++++++++++
                repackageDependent( filePath, rulesToApply );

            } else {

                try {
                    //Reading the file to check
                    FileInputStream inputStream = new FileInputStream( filePath );
                    String fileContent = IOUtils.toString( inputStream );

                    log( "Searching on " + filePath + " for packages strings." );

                    for ( Rule rule : rulesToApply.values() ) {
                        PackagerWildcard wildcard = new PackagerWildcard( rule );
                        fileContent = wildcard.replace( fileContent );
                    }

                    BufferedWriter writer = new BufferedWriter( new FileWriter( filePath ) );
                    writer.write( fileContent );
                    writer.close();

                } catch ( FileNotFoundException e ) {
                    throw new BuildException( "File " + filePath + " not found.", e );
                } catch ( IOException e ) {
                    throw new BuildException( "Error checking and/or modifying " + filePath + ".", e );
                }
            }
        }

        //++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //APPLY THE SAME RULES TO JSP's UNDER THE DOTCMS FOLDER
        //++++++++++++++++++++++++++++++++++++++++++++++++++++++
        File jspFolderFile = new File( getJspFolder() );

        log( "" );
        log( "-----------------------------------------" );
        log( "Updating JSP's" );

        for ( Rule rule : rulesToApply.values() ) {

            //Clean-up the pattern
            String pattern = rule.getPattern();
            pattern = pattern.replaceAll( "\\*\\*", "" );

            //Clean up the result
            String result = rule.getResult();
            if ( result.lastIndexOf( "@" ) != -1 ) {
                result = result.substring( 0, result.lastIndexOf( "@" ) );
            }

            String command = "find " + jspFolderFile.getAbsolutePath() + " -name '*.jsp' -exec sed -i 's/\"" + pattern + "/\"" + result + "/' {} \\;";
            log( command );

            try {
                Runtime.getRuntime().exec(
                        new String[]{
                                "sh", "-l", "-c", command
                        }
                );
            } catch ( IOException e ) {
                throw new BuildException( "Error replacing JSP's Strings: " + pattern + ".", e );
            }
        }
    }

    /**
     * Repackage a given jar that was using some of the packages we changed
     *
     * @param jarPath
     * @param rulesToApply
     */
    private void repackageDependent ( String jarPath, HashMap<String, Rule> rulesToApply ) {

        File jarFile = new File( jarPath );
        String jarName = jarFile.getName().substring( 0, jarFile.getName().lastIndexOf( "." ) );
        //String jarLocation = jarFile.getAbsolutePath().substring( 0, jarFile.getAbsolutePath().lastIndexOf( File.separator ) );

        //Prepare the jars that we are going to repackage
        Collection<File> files = new ArrayList<File>();
        files.add( jarFile );
        //-------------------------------
        //SOME LOGGING
        logJars( files );

        //Repackage the jar into a temporal file
        String tempJarName = jarName + "_temp" + ".jar";
        File tempJar = new File( getOutputFolder() + File.separator + tempJarName );
        generate( tempJar, rulesToApply.values(), files );

        /*
         Rename the just repackaged temporal jar
         */
        //If the jars live in the same folder delete the original jar in order to be able to rename the temporal file
        if ( jarFile.getParent().equals( tempJar.getParent() ) ) {
            //Remove the original jar
            jarFile.delete();
        }

        File finalJar = new File( tempJar.getParent() + File.separator + jarFile.getName() );
        tempJar.renameTo( finalJar );
    }

    /**
     * Repackage a list of given jars applying given rules
     *
     * @param outFile the output jar after processing
     * @param rules   rules to apply
     * @param jars    jars to integrate into one
     */
    private void generate ( File outFile, Collection<Rule> rules, Collection<File> jars ) {

        //Destiny file
        setDestFile( outFile );

        //Prepare the jars that we are going to repackage
        for ( File jar : jars ) {

            ZipFileSet fileSet = new ZipFileSet();
            fileSet.setSrc( jar );

            //Add it to the fileset
            addZipfileset( fileSet );
        }

        //Prepare the rules for these groups of jar
        List<PatternElement> patterns = new ArrayList<PatternElement>();
        for ( Rule rule : rules ) {
            addConfiguredRule( rule );
            patterns.add( rule );
        }

        //Generate the new jar
        MainProcessor processor = new MainProcessor( patterns, initialVerbose, false, initialRenameServices );
        execute( processor );
        try {
            processor.strip( getDestFile() );
        } catch ( IOException e ) {
            throw new BuildException( e );
        }
        //super.execute();

        //Clean everything and get ready to start again
        super.reset();
    }

    private void logJars ( Collection<File> jars ) {

        //-------------------------------
        //SOME LOGGING
        log( "" );
        log( "-----------------------------------------" );
        log( "Repackaging... " );
        for ( File jar : jars ) {
            log( jar.getName() );
        }
        log( "" );
        //-------------------------------
    }

    private void logRules ( Collection<Rule> rules ) {

        //-------------------------------
        //SOME LOGGING
        log( "" );
        log( "-----------------------------------------" );
        log( "Rules to apply: " );
        for ( Rule rule : rules ) {
            log( rule.getPattern() + " --> " + rule.getResult() );
        }
        //-------------------------------
    }

    /**
     * Return true if the name corresponds to a jar file
     *
     * @param name The file name
     * @return true if the name is a jar file, false otherwise.
     */
    private boolean isJarName ( String name ) {
        name = name.toLowerCase();
        return name.endsWith( ".jar" );
    }

    public String getDotVersion () {
        return dotVersion;
    }

    public void setDotVersion ( String dotVersion ) {
        this.dotVersion = dotVersion;
    }

    public String getOutputFolder () {
        return outputFolder;
    }

    public void setOutputFolder ( String outputFolder ) {
        this.outputFolder = outputFolder;
    }

    public String getOutputFile () {
        return outputFile;
    }

    public void setOutputFile ( String outputFile ) {
        this.outputFile = outputFile;
    }

    public String getJspFolder () {
        return jspFolder;
    }

    public void setJspFolder ( String jspFolder ) {
        this.jspFolder = jspFolder;
    }

    public String getDotcmsJar () {
        return dotcmsJar;
    }

    public void setDotcmsJar ( String dotcmsJar ) {
        this.dotcmsJar = dotcmsJar;
    }

    public boolean isMultipleJars () {
        return multipleJars;
    }

    public void setMultipleJars ( boolean multipleJars ) {
        this.multipleJars = multipleJars;
    }

    public boolean isGenerateRulesFromParentFolder () {
        return generateRulesFromParentFolder;
    }

    public void setGenerateRulesFromParentFolder ( boolean generateRulesFromParentFolder ) {
        this.generateRulesFromParentFolder = generateRulesFromParentFolder;
    }

    public boolean isSkipDependenciesReplacement () {
        return skipDependenciesReplacement;
    }

    public void setSkipDependenciesReplacement ( boolean skipDependenciesReplacement ) {
        this.skipDependenciesReplacement = skipDependenciesReplacement;
    }

    public void addFileset ( FileSet fileset ) {
        filesets.add( fileset );
    }

    public Dependency createDependency () {
        Dependency dependency = new Dependency();
        dependencies.add( dependency );

        return dependency;
    }

}