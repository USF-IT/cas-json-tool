package edu.usf.cas
	
import groovy.json.*
import org.springframework.util.AntPathMatcher
import org.jasig.cas.services.RegisteredService
import org.jasig.cas.services.RegisteredServiceImpl
import org.jasig.cas.services.RegexRegisteredService
import net.unicon.cas.addons.serviceregistry.RegexRegisteredServiceWithAttributes
import net.unicon.cas.addons.serviceregistry.RegisteredServiceWithAttributesImpl

/**
 * Parses/modifies JSON-based CAS service registry file.
 * @author Eric Pierce
 * @version 0.1
 */
class JsonServiceRegistryParser {

	private def jsonObject 
	private def clobber = false
	private File outputFile
	private def outputFileName
	private def casAttributes
	private def requiredAttributes
	private def defaultTheme
	private def extraServiceAttributes = [createdDate:String.format('%tF', new Date())]


	/*
	* Read the JSON file
	*/
	def JsonServiceRegistryParser(jsonFileName) {
		def text = new File(jsonFileName).text
		def slurper = new JsonSlurper()
		jsonObject = slurper.parseText(text)
	}

	def setClobber(clobberVal){
		clobber = clobberVal
	}

	def setOutput(fileName) {
		outputFile = new File(fileName)
		outputFileName = fileName
	}

	def setCasAttributes(attributes){
		casAttributes = attributes
	}

	def setDefaultTheme(theme){
		defaultTheme = theme
	}

	def setRequiredAttributes(attributes){
		requiredAttributes = attributes
	}

	def checkOptions(function,options){
		if((options.enable)&&(options.disable)) exitOnError('--disable and --enable cannot be used together')
		if((options.enableSSO)&&(options.disableSSO)) exitOnError('--disableSSO and --enableSSO cannot be used together')
		if((options.enableAnonymous)&&(options.disableAnonymous)) exitOnError('--disableAnonymous and --enableAnonymous cannot be used together')
		if((options.enableProxy)&&(options.disableProxy)) exitOnError('--disableProxy and --enableProxy cannot be used together')
		if((options.id)&&(! options.id.isInteger())) exitOnError('ServiceID must be an Integer')

		switch (function){
			case "new":
				if(!options.name) exitOnError('Service name required!') 
				if(!options.desc) exitOnError('Service description Required!') 
				if(!options.pattern) exitOnError('Regex or Ant pattern required!')
				if(!options.url) exitOnError('Test URL required!')
				if(options.release) checkCasAttributes(options.releases) 
				checkRequiredAttributes(options)
			break
			case "remove":
				if(!options.id) jsonParser.exitOnError('Service ID number required!') 
			break
			case "modify":
				if(!options.id) jsonParser.exitOnError('Service ID number required!') 
			break

		}
	}

	def checkCasAttributes(releaseList){
		releaseList.each { attribute ->
			if(! casAttributes.contains(attribute)) exitOnError("Attribute ${attribute} can not be released")
		}
	}

	def checkRequiredAttributes(options){
		requiredAttributes.each { attribute ->
			if(! options["${attribute}"]){
				exitOnError("Attribute ${attribute} is required!")
			} else {
				addExtraServiceAttribute(attribute,options["${attribute}"])
			}
		}
	}

	def findHighestId(){
		def idList = [] 
		jsonObject.services.each { service ->
			idList.add(service.id)
		}
		idList.max() as Integer
	}

	def addService(service){
		jsonObject.services.add(service)
	}

	def createRegisteredService(options){
		def jsonService
 		if(options.pattern.startsWith('^')){ 
      	jsonService = new RegexRegisteredServiceWithAttributes()
			def regexPattern = ~/${options.pattern}/
			if (! regexPattern.matcher(options.url).matches()) exitOnError('Test URL does not match pattern')
		}else{
			jsonService = new RegisteredServiceWithAttributesImpl()
			def matcher = new AntPathMatcher()
			if (! matcher.match(options.pattern.toLowerCase(), options.url.toLowerCase()))  exitOnError('Test URL does not match pattern')
		} 
		jsonService.with {
			setId findHighestId()+1
			setName options.name
			setDescription options.desc
			setServiceId options.pattern
			setTheme options.theme ?: defaultTheme
			setAllowedAttributes options.releases ?: []
			setExtraAttributes extraServiceAttributes
			if(options.disable) setEnabled false   //Services are enabled by default
			if(options.disableSSO) setSsoEnabled false   //SSO is enabled by default
			if(options.enableAnonymous) setAnonymousAccess true   //Anonymous is disabled by default
			setAllowedToProxy options.enableProxy ?: false   //Proxy is enabled by default in CAS, but IMHO it should be disabled by default
		}
		return jsonService
	}

	def addExtraServiceAttribute(name,value){	
		extraServiceAttributes.put(name,value)
	}


	def removeService(id){ 
		def removeThisService = findById(id)
		jsonObject.services.remove(removeThisService)
	}

	def modifyService(origService,options){
		//make sure the attributes (if requested) can be released 
		if(options.release) checkCasAttributes(options.releases)

		if(options.enable) origService.enabled = true
		if(options.disable) origService.enabled = false
		if(options.enableSSO) origService.ssoEnabled = true
		if(options.disableSSO) origService.ssoEnabled = false
		if(options.enableAnonymous) origService.anonymousAccess = true
		if(options.disableAnonymous) origService.anonymousAccess = false
		if(options.enableProxy) origService.allowedToProxy = true
		if(options.disableProxy) origService.allowedToProxy = false
		if(options.email) origService.extraAttributes.contactEmail = options.contactEmails
		if(options.dept) origService.extraAttributes.contactDept = options.contactDept
		if(options.contact) origService.extraAttributes.contactName = options.contactNames
		if(options.name) origService.name = options.name
		if(options.desc) origService.description = options.desc
		if(options.theme) origService.theme = options.theme
		if(options.release) origService.allowedAttributes = options.releases
		if(options.pattern) origService.serviceId = options.pattern
		origService.extraAttributes.modifiedDate = String.format('%tF', new Date())

		return origService
	}

	def searchForService(options){
		def jsonService

		if(options.contact){
		}else if(options.email){
			jsonService = findByEmail(options.email)
		}else if(options.dept){
			jsonService = findByDepartment(options.dept)
		}else if(options.url){
			jsonService = findByUrl(options.url)
		}else if(options.id){
			jsonService = findById(options.id)
		}else if(options.name){
			jsonService = findByName(options.name)
		}else {
			exitOnError('Contact name, email, department, url, id, or name required!')
		}
		return jsonService
	}

	def findById(id){
		def foundService = []
		jsonObject.services.each { service ->
			if(id.toInteger() == service.id) foundService.add(service) 
		}
		if (foundService.size != 1) exitOnError("Found ${foundService.size} Service IDs that match!")
		return foundService[0]
	}

	def findByName(namePattern){
		def foundService = []
		def pattern = ~/${namePattern}/
		jsonObject.services.each { service ->
			if(pattern.matcher(service.name).matches()) foundService.add(service) 
		}
		return foundService
	}

    def findByDepartment(deptPattern){
       def foundService = []
       def pattern = ~/${deptPattern}/
       jsonObject.services.each { service ->
			if(service.extraAttributes.contactDept){
          if(pattern.matcher(service.extraAttributes.contactDept).matches()) foundService.add(service) 
		   }
       }
       return foundService
    }

	def findByEmail(emailPattern){
		def foundService = []
		def pattern = ~/${emailPattern}/
		jsonObject.services.each { service ->
		  if(service.extraAttributes.contactEmail){
				service.extraAttributes.contactEmail.each { email ->
					if(pattern.matcher(email).matches()) foundService.add(service) 
				}
			}
		}
		return foundService
}

	def findByUrl(url){
		def foundService = []
		jsonObject.services.each { service ->
			if(service.serviceId.startsWith("^")){
				def pattern = ~/${service.serviceId}/
				if(pattern.matcher(url).matches()) foundService.add(service)
			} else {
				def matcher = new AntPathMatcher()
				if (matcher.match(service.serviceId.toLowerCase(), url.toLowerCase())) foundService.add(service)
			} 
		}
		return foundService
	}

	def printJSON() {
		printJSON(jsonObject)
	}

	def printJSON(service) {
		def jsonOut = new JsonOutput()

		//Write to a file
		if(outputFile){
			//Don't overwrite a file unless told to
			if((! clobber)&&(outputFile.exists())) {
				exitOnError("${outputFileName} already exists.  Use --force to overwrite.")
			} else {
				outputFile.write("${jsonOut.prettyPrint(jsonOut.toJson(service))}\n")
				println "Output written to ${outputFileName}\n"
				System.exit(0)		
			}
		//output to screen
		} else {
			println "${jsonOut.prettyPrint(jsonOut.toJson(service))}\n"
			System.exit(0)
		}

	}

	def exitOnError(errorString){
		println("\n${errorString}\n")
		System.exit(1)
	}
}